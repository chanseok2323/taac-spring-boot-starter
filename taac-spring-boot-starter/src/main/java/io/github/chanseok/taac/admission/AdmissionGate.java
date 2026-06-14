package io.github.chanseok.taac.admission;

/**
 * Permit data structure under the controller.
 *
 * <p>Two implementations ship by default: {@link SemaphoreAdmissionGate}
 * for weight=1 traffic and {@link DualQueueAdmissionGate} for token-weighted
 * SJF scheduling. Plug in your own (priority queue, fair-share scheduler,
 * cluster-aware gate, …) by registering an {@code AdmissionGate} bean.
 *
 * <p>Extends {@link AutoCloseable} because some implementations own
 * background threads — the autoconfiguration wires {@code destroyMethod="close"}.
 */
public interface AdmissionGate extends AutoCloseable {

    boolean acquire(int weight, long timeoutMs) throws InterruptedException;

    void release(int weight);

    /** Positive grows capacity, negative shrinks. Called from the refresh loop. */
    void resize(int delta);

    int availablePermits();
    int logicalCapacity();
    int getQueueLength();

    /** In-flight requests beyond the current capacity (only happens right after a shrink). */
    default int borrowedPermits() { return 0; }

    @Override
    default void close() {}
}
