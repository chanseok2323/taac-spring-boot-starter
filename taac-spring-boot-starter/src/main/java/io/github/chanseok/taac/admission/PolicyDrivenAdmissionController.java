package io.github.chanseok.taac.admission;

import io.github.chanseok.taac.memory.HeapMemoryMonitor;
import io.github.chanseok.taac.memory.MemorySnapshot;
import io.github.chanseok.taac.policy.ConcurrencyPolicy;
import io.github.chanseok.taac.policy.MemoryPressureLevel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The one controller. Wires a policy to a gate, runs the background refresh
 * loop, and emits events to a listener. Everything else is delegated:
 *
 * <ul>
 *   <li>{@link ConcurrencyPolicy} decides the target permit count and weight adjustments.</li>
 *   <li>{@link AdmissionGate} owns the queue/permit data structure.</li>
 *   <li>{@link AdmissionListener} observes (metrics, logging, …).</li>
 * </ul>
 *
 * <p>The class doesn't know which policy or gate it's driving. Add a new
 * policy by registering a {@code ConcurrencyPolicy} bean — this class
 * doesn't need to change.
 */
public final class PolicyDrivenAdmissionController implements AdmissionController {

    private static final Logger log = LoggerFactory.getLogger(PolicyDrivenAdmissionController.class);
    private static final int FAILURE_ESCALATE_THRESHOLD = 10;

    private final ConcurrencyPolicy policy;
    private final AdmissionGate gate;
    private final HeapMemoryMonitor memoryMonitor;
    private final AdmissionListener listener;
    private final long timeoutMs;
    private final int maxConcurrency;
    private final boolean dynamicCapacityFailFast;

    private final AtomicInteger currentPermitTarget;
    private final AtomicInteger consecutiveRefreshFailures = new AtomicInteger();
    private volatile MemorySnapshot cachedSnapshot = MemorySnapshot.EMPTY;
    private volatile MemoryPressureLevel cachedLevel = MemoryPressureLevel.LOW;

    public PolicyDrivenAdmissionController(ConcurrencyPolicy policy,
                                           AdmissionGate gate,
                                           HeapMemoryMonitor memoryMonitor,
                                           AdmissionListener listener,
                                           int maxConcurrency,
                                           long timeoutMs,
                                           boolean dynamicCapacityFailFast) {
        this.policy = policy;
        this.gate = gate;
        this.memoryMonitor = memoryMonitor;
        this.listener = listener;
        this.timeoutMs = timeoutMs;
        this.maxConcurrency = maxConcurrency;
        this.dynamicCapacityFailFast = dynamicCapacityFailFast;
        this.currentPermitTarget = new AtomicInteger(maxConcurrency);
    }

    @PostConstruct
    void primeCache() {
        refreshTarget();
    }

    @PreDestroy
    void shutdown() {
        try { gate.close(); } catch (Exception e) { log.warn("gate close failed: {}", e.toString()); }
    }

    // --- background refresh ---------------------------------------------------

    @Scheduled(fixedDelayString = "${taac.admission.eval-interval-ms:50}")
    public void refreshTarget() {
        try {
            MemorySnapshot snapshot = memoryMonitor.snapshot();
            ConcurrencyPolicy.GateState state = new ConcurrencyPolicy.GateState(
                    snapshot, gate.getQueueLength(), currentPermitTarget.get());

            int target = policy.evaluate(state);
            int current = currentPermitTarget.get();
            if (current != target && currentPermitTarget.compareAndSet(current, target)) {
                gate.resize(target - current);
            }

            cachedSnapshot = snapshot;
            cachedLevel = policy.pressureLevel(snapshot);
            consecutiveRefreshFailures.set(0);
        } catch (RuntimeException e) {
            int n = consecutiveRefreshFailures.incrementAndGet();
            if (n >= FAILURE_ESCALATE_THRESHOLD) {
                log.error("refreshTarget failed {} times in a row — control plane frozen: {}", n, e.toString());
            } else {
                log.warn("refreshTarget failed (#{}) — using stale cache: {}", n, e.toString());
            }
        }
    }

    public int consecutiveRefreshFailures() { return consecutiveRefreshFailures.get(); }

    // --- hot path -------------------------------------------------------------

    @Override
    public AdmissionToken acquire(int requestedWeight) {
        int weight = Math.max(1, policy.adaptWeight(Math.max(1, requestedWeight)));

        if (weight > maxConcurrency) {
            return failFast(weight, AdmissionEvent.FailureReason.OVER_CAPACITY,
                    "weight " + weight + " > maxConcurrency " + maxConcurrency);
        }
        if (dynamicCapacityFailFast) {
            int logical = gate.logicalCapacity();
            if (weight > logical && logical > 0) {
                return failFast(weight, AdmissionEvent.FailureReason.DYNAMIC_CAPACITY_FAIL_FAST,
                        "weight " + weight + " > current logical capacity " + logical);
            }
        }

        listener.onAcquireRequested(new AdmissionEvent.AcquireRequested(weight));
        long startNs = System.nanoTime();
        boolean ok;
        try {
            ok = gate.acquire(weight, timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return failFast(weight, AdmissionEvent.FailureReason.INTERRUPTED, "interrupted while waiting");
        }
        if (!ok) {
            String detail = "timeout " + timeoutMs + "ms, weight=" + weight
                    + ", heap=" + String.format("%.1f%%", cachedSnapshot.usagePercent());
            return failFast(weight, AdmissionEvent.FailureReason.TIMEOUT, detail);
        }

        long waitMs = (System.nanoTime() - startNs) / 1_000_000;
        AdmissionResult info = new AdmissionResult(
                waitMs, currentPermitTarget.get(), cachedLevel, cachedSnapshot.usagePercent());
        listener.onAcquired(new AdmissionEvent.Acquired(
                weight, waitMs, info.permittedConcurrency(), info.pressureLevel(), info.heapUsagePercent()));
        return new IssuedToken(weight, startNs, info);
    }

    private AdmissionToken failFast(int weight, AdmissionEvent.FailureReason reason, String detail) {
        listener.onAcquireFailed(new AdmissionEvent.AcquireFailed(weight, reason, detail));
        throw new AdmissionTimeoutException(detail);
    }

    // --- diagnostics ----------------------------------------------------------

    public int availablePermits()      { return gate.availablePermits(); }
    public int queueLength()           { return gate.getQueueLength(); }
    public int logicalCapacity()       { return gate.logicalCapacity(); }
    public int borrowedPermits()       { return gate.borrowedPermits(); }
    public int currentPermitTarget()   { return currentPermitTarget.get(); }

    // --- token impl -----------------------------------------------------------

    private final class IssuedToken implements AdmissionToken {
        private final int weight;
        private final long acquiredNs;
        private final AdmissionResult info;
        private final AtomicBoolean released = new AtomicBoolean();

        IssuedToken(int weight, long acquiredNs, AdmissionResult info) {
            this.weight = weight;
            this.acquiredNs = acquiredNs;
            this.info = info;
        }

        @Override public AdmissionResult result() { return info; }

        @Override
        public void recordCompletion(int inputTokens, int outputTokens) {
            long elapsedMs = (System.nanoTime() - acquiredNs) / 1_000_000;
            recordCompletion(elapsedMs, inputTokens, outputTokens);
        }

        @Override
        public void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens) {
            policy.recordCompletion(responseTimeMs, inputTokens, outputTokens);
            listener.onCompletion(new AdmissionEvent.Completed(responseTimeMs, inputTokens, outputTokens));
        }

        @Override
        public void close() {
            if (!released.compareAndSet(false, true)) return;
            gate.release(weight);
            listener.onReleased(new AdmissionEvent.Released(weight));
        }
    }
}
