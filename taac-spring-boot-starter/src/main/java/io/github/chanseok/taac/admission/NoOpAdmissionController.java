package io.github.chanseok.taac.admission;

import io.github.chanseok.taac.policy.MemoryPressureLevel;

/**
 * Pass-through controller for {@code taac.admission.policy=baseline}.
 * Useful as an A/B baseline against the active policies.
 */
public final class NoOpAdmissionController implements AdmissionController {

    private static final AdmissionResult IMMEDIATE =
            new AdmissionResult(0L, Integer.MAX_VALUE, MemoryPressureLevel.LOW, 0.0);

    private static final AdmissionToken TOKEN = new AdmissionToken() {
        @Override public AdmissionResult result() { return IMMEDIATE; }
        @Override public void recordCompletion(int in, int out) {}
        @Override public void recordCompletion(long ms, int in, int out) {}
        @Override public void close() {}
    };

    @Override
    public AdmissionToken acquire(int weight) {
        return TOKEN;
    }
}
