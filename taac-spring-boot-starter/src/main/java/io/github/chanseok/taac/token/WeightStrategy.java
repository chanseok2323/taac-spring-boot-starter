package io.github.chanseok.taac.token;

/**
 * Turns an input-token count into a permit weight. {@link TokenWeightTracker}
 * is the default — replace it by registering your own {@code WeightStrategy}
 * bean (e.g. a static lookup table, or a strategy backed by a real tokenizer).
 */
public interface WeightStrategy {

    /** 1 ≤ weight ≤ {@link #maxWeight()}. */
    int weightFor(int inputTokens);

    /** Optional hook to feed observed token counts back into the strategy. */
    default void recordInput(int inputTokens) {}

    int maxWeight();
}
