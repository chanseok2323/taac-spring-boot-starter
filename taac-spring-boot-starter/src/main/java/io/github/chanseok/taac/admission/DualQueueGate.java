package io.github.chanseok.taac.admission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Single-FIFO weighted-permit admission gate (lock-free hot path, dedicated scheduler).
 *
 * ── 설계 ──
 *
 * Producer-consumer 구조:
 *   - acquire/release 스레드 (producer): lock-free CAS + ConcurrentLinkedQueue에 offer, 그 후 park
 *   - 전용 scheduler thread (consumer): incoming → active deque drain, 스케줄링 결정,
 *     CAS로 grant, unpark로 waiter 깨움
 *
 * ── 학문적 근거 ──
 *
 * Producer-consumer / SEDA (Staged Event-Driven Architecture, Welsh 2001) 계보.
 * 요청 스레드와 스케줄링 스레드를 분리해 hot path 경합 제거.
 *
 * Single queue + bounded SJF (Shortest-Job-First) scan + aging reserve:
 * 큐 앞쪽 N 개 waiter 중 capacity 에 맞는 것 중 **가장 작은 weight 우선** admit.
 * 평균 대기시간 단축 (SRPT, Schrage 1968 의 LLM token-aware 적응).
 *
 * Aging reserve 로 head starvation 방지 — head 가 AGING_MS 이상 대기 시
 * 무조건 head 우선 처리 (capacity 맞으면 admit, 아니면 reserve).
 */
public class DualQueueGate {

    private static final Logger log = LoggerFactory.getLogger(DualQueueGate.class);

    private static final int BACKFILL_SCAN = 8;
    private static final int SWEEP_INTERVAL = 32;
    private static final int SWEEP_BUDGET = 4;
    private static final long HEAD_AGING_MS = 500;

    private final AtomicInteger logicalCapacity;
    private final AtomicInteger inUseWeight = new AtomicInteger(0);
    private final AtomicInteger activeCount = new AtomicInteger(0);

    private final ConcurrentLinkedQueue<Waiter> incoming = new ConcurrentLinkedQueue<>();

    private final Deque<Waiter> active = new ArrayDeque<>();
    private int grantCounterSinceSweep = 0;

    private final Thread schedulerThread;
    private volatile boolean running = true;

    public enum UnderflowMode { LOG_ONLY, STRICT }
    private final UnderflowMode underflowMode;

    private final long schedulerIdleParkNs;
    private final boolean fastPathEnabled;

    public DualQueueGate(int initialCapacity, boolean fair) {
        this(initialCapacity, fair, UnderflowMode.LOG_ONLY,
                TimeUnit.MILLISECONDS.toNanos(50), true);
    }

    public DualQueueGate(int initialCapacity, boolean fair, UnderflowMode underflowMode) {
        this(initialCapacity, fair, underflowMode,
                TimeUnit.MILLISECONDS.toNanos(50), true);
    }

    public DualQueueGate(int initialCapacity, boolean fair, UnderflowMode underflowMode,
                         long schedulerIdleParkNs, boolean fastPathEnabled) {
        if (initialCapacity < 0) {
            throw new IllegalArgumentException("initialCapacity < 0: " + initialCapacity);
        }
        this.logicalCapacity = new AtomicInteger(initialCapacity);
        this.underflowMode = underflowMode;
        this.schedulerIdleParkNs = schedulerIdleParkNs;
        this.fastPathEnabled = fastPathEnabled;
        this.schedulerThread = new Thread(this::schedulerLoop, "taac-DualQueueScheduler");
        this.schedulerThread.setDaemon(true);
        this.schedulerThread.start();
    }

    private static class Waiter {
        final int weight;
        final long enqueueTimeNanos;
        final Thread thread;
        volatile boolean granted = false;
        volatile boolean cancelled = false;

        Waiter(int weight, Thread thread) {
            this.weight = weight;
            this.enqueueTimeNanos = System.nanoTime();
            this.thread = thread;
        }
    }

    public boolean acquire(int weight, long timeoutMs) throws InterruptedException {
        if (weight < 1) weight = 1;

        if (fastPathEnabled && isEmpty()) {
            for (int spin = 0; spin < 3; spin++) {
                int cur = inUseWeight.get();
                int cap = logicalCapacity.get();
                if (cur + weight > cap) break;
                if (inUseWeight.compareAndSet(cur, cur + weight)) {
                    return true;
                }
            }
        }

        Waiter w = new Waiter(weight, Thread.currentThread());
        incoming.offer(w);
        activeCount.incrementAndGet();

        LockSupport.unpark(schedulerThread);

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!w.granted) {
            if (Thread.interrupted()) {
                w.cancelled = true;
                decrementActive(weight);
                throw new InterruptedException();
            }
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                w.cancelled = true;
                decrementActive(weight);
                return false;
            }
            LockSupport.parkNanos(this, remaining);
        }
        return true;
    }

    public void release(int weight) {
        if (weight < 1) weight = 1;
        int newVal = inUseWeight.addAndGet(-weight);
        if (newVal < 0) {
            if (underflowMode == UnderflowMode.STRICT) {
                inUseWeight.addAndGet(weight);
                throw new IllegalStateException(
                        "inUseWeight underflow: " + newVal + " (weight=" + weight + ")");
            }
            inUseWeight.compareAndSet(newVal, 0);
            log.warn("inUseWeight underflow: {} (weight={})", newVal, weight);
        }
        LockSupport.unpark(schedulerThread);
    }

    public void reducePermits(int reduction) {
        if (reduction < 0) throw new IllegalArgumentException("reduction < 0: " + reduction);
        if (reduction == 0) return;
        int cur;
        do {
            cur = logicalCapacity.get();
            if (reduction > cur) {
                throw new IllegalArgumentException(
                        "reduction " + reduction + " > logicalCapacity " + cur);
            }
        } while (!logicalCapacity.compareAndSet(cur, cur - reduction));
    }

    public void addPermits(int addition) {
        if (addition < 0) throw new IllegalArgumentException("addition < 0: " + addition);
        if (addition == 0) return;
        logicalCapacity.addAndGet(addition);
        LockSupport.unpark(schedulerThread);
    }

    public void shutdown() {
        running = false;
        LockSupport.unpark(schedulerThread);
    }

    private void schedulerLoop() {
        while (running) {
            try {
                drainIncoming();
                scheduleGrants();
            } catch (Throwable t) {
                log.error("scheduler loop error", t);
            }
            if (hasPendingWaiters()) {
                LockSupport.parkNanos(this, schedulerIdleParkNs);
            } else {
                LockSupport.park(this);
            }
        }
    }

    private void drainIncoming() {
        Waiter w;
        while ((w = incoming.poll()) != null) active.addLast(w);
    }

    private boolean hasPendingWaiters() {
        return !active.isEmpty() || !incoming.isEmpty();
    }

    private void scheduleGrants() {
        while (true) {
            Waiter next = pickNext();
            if (next == null) break;
            if (!tryGrant(next)) {
                if (next.cancelled) continue;
                active.addFirst(next);
                break;
            }
            if (++grantCounterSinceSweep >= SWEEP_INTERVAL) {
                grantCounterSinceSweep = 0;
                sweepBudgeted();
            }
        }
    }

    private Waiter pickNext() {
        purgeCancelledHeads();
        if (active.isEmpty()) return null;

        int cur = inUseWeight.get();
        int cap = logicalCapacity.get();
        Waiter head = active.peekFirst();

        long headWaitMs = (System.nanoTime() - head.enqueueTimeNanos) / 1_000_000;
        if (headWaitMs >= HEAD_AGING_MS) {
            if (cur + head.weight <= cap) {
                active.removeFirst();
                return head;
            }
            return null;
        }

        if (head.weight == 1 && cur + 1 <= cap) {
            active.removeFirst();
            return head;
        }

        Iterator<Waiter> it = active.iterator();
        int scanned = 0;
        Waiter best = null;
        while (it.hasNext() && scanned < BACKFILL_SCAN) {
            Waiter cand = it.next();
            if (cand.cancelled) { it.remove(); continue; }
            scanned++;
            if (cur + cand.weight <= cap) {
                if (best == null || cand.weight < best.weight) {
                    best = cand;
                    if (best.weight == 1) break;
                }
            }
        }
        if (best != null) {
            active.remove(best);
            return best;
        }
        return null;
    }

    private void purgeCancelledHeads() {
        while (!active.isEmpty() && active.peekFirst().cancelled) {
            active.removeFirst();
        }
    }

    private void sweepBudgeted() {
        Iterator<Waiter> it = active.iterator();
        int scanned = 0;
        while (it.hasNext() && scanned < SWEEP_BUDGET) {
            Waiter w = it.next();
            scanned++;
            if (w.cancelled) it.remove();
        }
    }

    private boolean tryGrant(Waiter w) {
        int cur;
        do {
            cur = inUseWeight.get();
            int cap = logicalCapacity.get();
            if (cur + w.weight > cap) return false;
        } while (!inUseWeight.compareAndSet(cur, cur + w.weight));

        decrementActive(w.weight);
        w.granted = true;
        LockSupport.unpark(w.thread);
        return true;
    }

    private void decrementActive(int weight) {
        activeCount.decrementAndGet();
    }

    private boolean isEmpty() {
        return incoming.isEmpty() && active.isEmpty();
    }

    public int availablePermits() {
        return Math.max(0, logicalCapacity.get() - inUseWeight.get());
    }

    public int logicalCapacity() {
        return logicalCapacity.get();
    }

    public int getQueueLength() {
        return activeCount.get();
    }

    public int borrowedPermits() {
        return 0;
    }
}
