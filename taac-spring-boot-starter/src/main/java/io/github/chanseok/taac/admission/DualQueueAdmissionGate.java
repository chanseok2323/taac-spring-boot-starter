package io.github.chanseok.taac.admission;

import java.util.concurrent.TimeUnit;

/**
 * Token-weighted SJF gate with aging. Lock-free hot path and a dedicated
 * scheduler thread — see {@link DualQueueGate} for the algorithm. Used by
 * the {@code vegas} policy.
 */
public final class DualQueueAdmissionGate implements AdmissionGate {

    private final DualQueueGate gate;

    public DualQueueAdmissionGate(int initialCapacity,
                                  boolean fair,
                                  String underflowMode,
                                  long schedulerIdleParkMs,
                                  boolean fastPathEnabled) {
        DualQueueGate.UnderflowMode mode = "strict".equalsIgnoreCase(underflowMode)
                ? DualQueueGate.UnderflowMode.STRICT
                : DualQueueGate.UnderflowMode.LOG_ONLY;
        this.gate = new DualQueueGate(initialCapacity, fair, mode,
                TimeUnit.MILLISECONDS.toNanos(schedulerIdleParkMs),
                fastPathEnabled);
    }

    @Override
    public boolean acquire(int weight, long timeoutMs) throws InterruptedException {
        return gate.acquire(Math.max(1, weight), timeoutMs);
    }

    @Override
    public void release(int weight) {
        gate.release(Math.max(1, weight));
    }

    @Override
    public void resize(int delta) {
        if (delta > 0) gate.addPermits(delta);
        else if (delta < 0) gate.reducePermits(-delta);
    }

    @Override public int availablePermits() { return gate.availablePermits(); }
    @Override public int logicalCapacity()  { return gate.logicalCapacity(); }
    @Override public int getQueueLength()   { return gate.getQueueLength(); }
    @Override public int borrowedPermits()  { return gate.borrowedPermits(); }

    @Override
    public void close() {
        gate.shutdown();
    }
}
