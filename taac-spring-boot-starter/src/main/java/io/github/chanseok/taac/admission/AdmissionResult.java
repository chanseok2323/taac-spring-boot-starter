package io.github.chanseok.taac.admission;

import io.github.chanseok.taac.policy.MemoryPressureLevel;

/** Metadata captured at the moment a slot was granted. */
public record AdmissionResult(
        long waitTimeMs,
        int permittedConcurrency,
        MemoryPressureLevel pressureLevel,
        double heapUsagePercent
) {
}
