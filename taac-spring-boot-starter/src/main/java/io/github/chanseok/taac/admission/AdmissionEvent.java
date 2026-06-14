package io.github.chanseok.taac.admission;

import io.github.chanseok.taac.policy.MemoryPressureLevel;

/**
 * Events the controller emits to {@link AdmissionListener}s.
 *
 * <p>Sealed so callers can {@code switch} on the variant in Java's pattern
 * matching syntax; add a new record here when adding a new event kind.
 */
public sealed interface AdmissionEvent {

    enum FailureReason {
        TIMEOUT, OVER_CAPACITY, DYNAMIC_CAPACITY_FAIL_FAST, INTERRUPTED
    }

    record AcquireRequested(int weight) implements AdmissionEvent {}

    record Acquired(int weight,
                    long waitTimeMs,
                    int permittedConcurrency,
                    MemoryPressureLevel pressureLevel,
                    double heapUsagePercent) implements AdmissionEvent {}

    record AcquireFailed(int weight,
                         FailureReason reason,
                         String detail) implements AdmissionEvent {}

    record Released(int weight) implements AdmissionEvent {}

    record Completed(long responseTimeMs,
                     int inputTokens,
                     int outputTokens) implements AdmissionEvent {}
}
