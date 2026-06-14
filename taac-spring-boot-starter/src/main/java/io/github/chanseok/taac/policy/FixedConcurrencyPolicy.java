package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;

/**
 * Constant permit count, no signals. Useful as a "control with no adaptation"
 * baseline against the adaptive policies.
 */
public class FixedConcurrencyPolicy implements ConcurrencyPolicy {

    private final int maxConcurrency;

    public FixedConcurrencyPolicy(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    @Override
    public int evaluate(GateState state) {
        return maxConcurrency;
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        return MemoryPressureLevel.LOW;
    }
}
