package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;

/**
 * Decides how many slots the admission gate should expose at any moment.
 *
 * <p>The controller calls {@link #evaluate(GateState)} every refresh tick
 * and resizes the gate to match. Override {@link #recordCompletion} if the
 * policy needs response-time / token signals, and {@link #adaptWeight} to
 * influence what weight an incoming request actually pays.
 */
public interface ConcurrencyPolicy {

    /** Snapshot of everything an evaluation might want to know about the gate. */
    record GateState(MemorySnapshot snapshot, int queueLength, int currentTarget) {}

    /** New permit target. Called periodically; should be cheap. */
    int evaluate(GateState state);

    /** Pressure level for the given heap snapshot — used by listeners/metrics. */
    MemoryPressureLevel pressureLevel(MemorySnapshot snapshot);

    /** Feed a finished request's response time and token counts back into the policy. */
    default void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens) {}

    /**
     * Last chance to revise the incoming weight before the gate sees it.
     * Used by token-aware Vegas to collapse weight to 1 when the backend isn't congested.
     */
    default int adaptWeight(int requestedWeight) {
        return requestedWeight;
    }
}
