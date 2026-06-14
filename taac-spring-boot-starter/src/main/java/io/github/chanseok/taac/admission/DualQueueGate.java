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
 * Weighted-permit admission gate with a single FIFO queue and a dedicated
 * scheduler thread.
 *
 * <p>Producer–consumer (SEDA, Welsh 2001): {@code acquire} / {@code release}
 * stay lock-free on the hot path; the scheduler thread owns the queue and
 * the grant decisions. Within a bounded scan window it picks the smallest
 * fitting weight (SRPT-ish, Schrage 1968 adapted for token weights), with an
 * aging reserve so the head of the queue can't be starved indefinitely.
 */
public class DualQueueGate {

    private static final Logger log = LoggerFactory.getLogger(DualQueueGate.class);

    /** How far into the queue the scheduler looks for a smaller weight that fits. */
    private static final int BACKFILL_SCAN = 8;
    private static final int SWEEP_INTERVAL = 32;
    private static final int SWEEP_BUDGET   = 4;
    /** Head waits this long before scheduling stops backfilling past it. */
    private static final long HEAD_AGING_MS = 500;

    // --- shared, lock-free state ---------------------------------------------
    private final AtomicInteger logicalCapacity;
    private final AtomicInteger inUseWeight = new AtomicInteger();
    private final AtomicInteger activeCount = new AtomicInteger();
    private final ConcurrentLinkedQueue<Waiter> incoming = new ConcurrentLinkedQueue<>();

    // --- scheduler-thread-only state -----------------------------------------
    private final Deque<Waiter> active = new ArrayDeque<>();
    private int grantsSinceSweep;

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
        this.logicalCapacity     = new AtomicInteger(initialCapacity);
        this.underflowMode       = underflowMode;
        this.schedulerIdleParkNs = schedulerIdleParkNs;
        this.fastPathEnabled     = fastPathEnabled;
        this.schedulerThread     = new Thread(this::schedulerLoop, "taac-DualQueueScheduler");
        this.schedulerThread.setDaemon(true);
        this.schedulerThread.start();
    }

    private static final class Waiter {
        final int weight;
        final long enqueuedAtNs;
        final Thread thread;
        volatile boolean granted;
        volatile boolean cancelled;

        Waiter(int weight, Thread thread) {
            this.weight = weight;
            this.enqueuedAtNs = System.nanoTime();
            this.thread = thread;
        }
    }

    // --- producer API ---------------------------------------------------------

    public boolean acquire(int weight, long timeoutMs) throws InterruptedException {
        if (weight < 1) weight = 1;

        if (fastPathEnabled && isEmpty()) {
            for (int spin = 0; spin < 3; spin++) {
                int cur = inUseWeight.get();
                if (cur + weight > logicalCapacity.get()) break;
                if (inUseWeight.compareAndSet(cur, cur + weight)) {
                    return true;
                }
            }
        }

        Waiter w = new Waiter(weight, Thread.currentThread());
        incoming.offer(w);
        activeCount.incrementAndGet();
        LockSupport.unpark(schedulerThread);

        long deadlineNs = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!w.granted) {
            if (Thread.interrupted()) {
                w.cancelled = true;
                activeCount.decrementAndGet();
                throw new InterruptedException();
            }
            long remaining = deadlineNs - System.nanoTime();
            if (remaining <= 0) {
                w.cancelled = true;
                activeCount.decrementAndGet();
                return false;
            }
            LockSupport.parkNanos(this, remaining);
        }
        return true;
    }

    public void release(int weight) {
        if (weight < 1) weight = 1;
        int updated = inUseWeight.addAndGet(-weight);
        if (updated < 0) {
            if (underflowMode == UnderflowMode.STRICT) {
                inUseWeight.addAndGet(weight);
                throw new IllegalStateException(
                        "inUseWeight underflow: " + updated + " (weight=" + weight + ")");
            }
            inUseWeight.compareAndSet(updated, 0);
            log.warn("inUseWeight underflow: {} (weight={})", updated, weight);
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

    // --- scheduler loop -------------------------------------------------------

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
                // Lost the capacity to a fast-path acquire or a shrink. Put the
                // waiter back at the head so we don't strand it and try again
                // next cycle once permits free up.
                if (next.cancelled) continue;
                active.addFirst(next);
                break;
            }
            if (++grantsSinceSweep >= SWEEP_INTERVAL) {
                grantsSinceSweep = 0;
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

        // Aging: a head that has waited too long jumps the SJF scan.
        long headWaitMs = (System.nanoTime() - head.enqueuedAtNs) / 1_000_000;
        if (headWaitMs >= HEAD_AGING_MS) {
            if (cur + head.weight <= cap) {
                active.removeFirst();
                return head;
            }
            return null;
        }

        // Fast path: head is already the smallest possible weight.
        if (head.weight == 1 && cur + 1 <= cap) {
            active.removeFirst();
            return head;
        }

        // SJF-ish scan within a bounded window.
        Iterator<Waiter> it = active.iterator();
        int scanned = 0;
        Waiter best = null;
        while (it.hasNext() && scanned < BACKFILL_SCAN) {
            Waiter cand = it.next();
            if (cand.cancelled) { it.remove(); continue; }
            scanned++;
            if (cur + cand.weight <= cap && (best == null || cand.weight < best.weight)) {
                best = cand;
                if (best.weight == 1) break;
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
            if (cur + w.weight > logicalCapacity.get()) return false;
        } while (!inUseWeight.compareAndSet(cur, cur + w.weight));

        activeCount.decrementAndGet();
        w.granted = true;
        LockSupport.unpark(w.thread);
        return true;
    }

    private boolean isEmpty() {
        return incoming.isEmpty() && active.isEmpty();
    }

    // --- diagnostics ----------------------------------------------------------

    public int availablePermits() { return Math.max(0, logicalCapacity.get() - inUseWeight.get()); }
    public int logicalCapacity()  { return logicalCapacity.get(); }
    public int getQueueLength()   { return activeCount.get(); }
    public int borrowedPermits()  { return 0; }
}
