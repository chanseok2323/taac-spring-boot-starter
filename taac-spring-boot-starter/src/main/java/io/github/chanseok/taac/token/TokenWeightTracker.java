package io.github.chanseok.taac.token;

import java.util.concurrent.atomic.AtomicLong;

/**
 * EMA-based weight strategy. Tracks the rolling average of input tokens
 * and reports weight as {@code ceil(tokens / avg)} clamped to
 * {@code [1, maxWeight]}. Average updates use α=0.1 so the weight doesn't
 * over-react to outliers.
 */
public class TokenWeightTracker implements WeightStrategy {

    private static final double ALPHA = 0.1;
    private static final double ONE_MINUS_ALPHA = 1.0 - ALPHA;
    private static final long   SCALE = 1000L;

    private final int defaultAvgTokens;
    private final int maxWeight;

    private volatile long avgTokensScaled;
    private final AtomicLong recordCount = new AtomicLong();

    public TokenWeightTracker(int defaultAvgTokens, int maxWeight) {
        this.defaultAvgTokens = defaultAvgTokens;
        this.maxWeight        = maxWeight;
        this.avgTokensScaled  = (long) defaultAvgTokens * SCALE;
    }

    @Override
    public int weightFor(int inputTokens) {
        long avg = avgTokensScaled / SCALE;
        if (avg <= 0) avg = defaultAvgTokens;
        int weight = (int) Math.ceil((double) inputTokens / avg);
        return Math.min(maxWeight, Math.max(1, weight));
    }

    @Override
    public void recordInput(int inputTokens) {
        if (inputTokens <= 0) return;
        recordCount.incrementAndGet();
        long scaled = (long) inputTokens * SCALE;
        avgTokensScaled = (long) (ALPHA * scaled + ONE_MINUS_ALPHA * avgTokensScaled);
    }

    @Override
    public int maxWeight() { return maxWeight; }

    public int currentAvgTokens() { return (int) (avgTokensScaled / SCALE); }
}
