package io.github.chanseok.taac.policy;

/** Maps a heap usage ratio to one of the four pressure bands. */
final class HeapPressureClassifier {

    private HeapPressureClassifier() {}

    static MemoryPressureLevel classify(double usageRatio,
                                        double moderate, double high, double critical) {
        if (usageRatio >= critical) return MemoryPressureLevel.CRITICAL;
        if (usageRatio >= high)     return MemoryPressureLevel.HIGH;
        if (usageRatio >= moderate) return MemoryPressureLevel.MODERATE;
        return MemoryPressureLevel.LOW;
    }
}
