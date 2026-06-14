package io.github.chanseok.taac.policy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EMA of response time (α=0.3) with a 10-sample warmup baseline.
 *
 * <p>The baseline is captured during a quiet period and bumped by 3× so a
 * stable load doesn't immediately look "slow" against an artificially low
 * reference. Used by {@link ResponseTimeBasedConcurrencyPolicy}.
 */
public class ResponseTimeTracker {

    private static final double ALPHA = 0.3;
    private static final double ONE_MINUS_ALPHA = 1.0 - ALPHA;
    private static final long   SCALE = 1000L;
    private static final int    WARMUP_COUNT = 10;

    private final AtomicLong    emaScaled  = new AtomicLong();
    private final AtomicLong    baselineMs = new AtomicLong();
    private final AtomicLong    warmupSum  = new AtomicLong();
    private final AtomicInteger count      = new AtomicInteger();

    public void record(long responseTimeMs) {
        int n = count.incrementAndGet();
        long newScaled = responseTimeMs * SCALE;

        if (n <= WARMUP_COUNT) {
            warmupSum.addAndGet(responseTimeMs);
            emaScaled.set(newScaled);
            if (n == WARMUP_COUNT) {
                baselineMs.set((warmupSum.get() / WARMUP_COUNT) * 3);
            }
            return;
        }

        long oldEma, updated;
        do {
            oldEma  = emaScaled.get();
            updated = (long) (ALPHA * newScaled + ONE_MINUS_ALPHA * oldEma);
        } while (!emaScaled.compareAndSet(oldEma, updated));
    }

    public double ratioToBaseline() {
        long baseline = baselineMs.get();
        return baseline <= 0 ? 1.0 : (double) (emaScaled.get() / SCALE) / baseline;
    }

    public boolean isWarmedUp() { return count.get() >= WARMUP_COUNT; }
    public long    currentEmaMs() { return emaScaled.get() / SCALE; }
    public int     totalCount() { return count.get(); }
    public long    baselineMs()  { return baselineMs.get(); }
}
