package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Baseline TCP Vegas (Brakmo &amp; Peterson, 1995) ported to admission
 * control with no domain adaptations — used to isolate the contribution
 * of the token-aware extensions in {@link TokenAwareVegasPolicy}.
 *
 * <p>Signal is the raw average response time; heap is reported but doesn't
 * influence the target.
 */
public class PureVegasPolicy implements ConcurrencyPolicy {

    private static final Logger log = LoggerFactory.getLogger(PureVegasPolicy.class);

    private static final double BETA  = 1.10;
    private static final double ALPHA = 0.95;
    private static final double MD_FACTOR = 0.70;
    private static final double HOLD_EMA  = 0.20;
    private static final int    EVAL_INTERVAL  = 5;
    private static final int    WARMUP_SAMPLES = 3;
    private static final double WARMUP_BUFFER  = 2.0;

    private final int maxConcurrency;
    private final int minConcurrency;
    private final double moderateHeap;
    private final double highHeap;
    private final double criticalHeap;

    private final AtomicLong responseTimeSum = new AtomicLong();
    private final AtomicLong completionCount = new AtomicLong();
    private volatile long lastEvalCount;

    private volatile int    warmupSeen;
    private volatile double warmupSum;
    private volatile double baseline;

    private final AtomicInteger currentTarget;
    private final ReentrantLock adjustLock = new ReentrantLock();

    public PureVegasPolicy(int maxConcurrency,
                           int minConcurrency,
                           double moderateHeap,
                           double highHeap,
                           double criticalHeap) {
        this.maxConcurrency = maxConcurrency;
        this.minConcurrency = minConcurrency;
        this.moderateHeap   = moderateHeap;
        this.highHeap       = highHeap;
        this.criticalHeap   = criticalHeap;
        this.currentTarget  = new AtomicInteger(maxConcurrency);
    }

    @Override
    public void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens) {
        responseTimeSum.addAndGet(responseTimeMs);
        long count = completionCount.incrementAndGet();
        if (count - lastEvalCount >= EVAL_INTERVAL) {
            adjustTarget();
        }
    }

    @Override
    public int evaluate(GateState state) {
        return currentTarget.get();
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        return HeapPressureClassifier.classify(snapshot.usageRatio(), moderateHeap, highHeap, criticalHeap);
    }

    public int currentTarget() { return currentTarget.get(); }

    // --- internals ----------------------------------------------------------

    private enum Reason { DECREASE, HOLD, INCREASE }

    private void adjustTarget() {
        if (!adjustLock.tryLock()) return;
        try {
            long totalTime = responseTimeSum.getAndSet(0);
            long sampled   = completionCount.get();
            long sinceLast = sampled - lastEvalCount;
            if (sinceLast < EVAL_INTERVAL) {
                responseTimeSum.addAndGet(totalTime);
                return;
            }
            lastEvalCount = sampled;

            double signal = sinceLast > 0 ? (double) totalTime / sinceLast : 0;

            if (warmupSeen < WARMUP_SAMPLES) {
                warmupSum += signal;
                if (++warmupSeen == WARMUP_SAMPLES) {
                    baseline = (warmupSum / WARMUP_SAMPLES) * WARMUP_BUFFER;
                    log.info("warmup done — baseline={} (sample_avg={})",
                            fmt(baseline), fmt(warmupSum / WARMUP_SAMPLES));
                }
                return;
            }

            int prev = currentTarget.get();
            int next = prev;
            Reason reason = Reason.HOLD;
            if (baseline > 0) {
                if (signal > baseline * BETA) {
                    next = Math.max(minConcurrency, (int) (prev * MD_FACTOR));
                    reason = Reason.DECREASE;
                } else if (signal < baseline * ALPHA) {
                    next = Math.min(maxConcurrency, prev + 2);
                    reason = Reason.INCREASE;
                }
            }

            double prevBaseline = baseline;
            baseline = switch (reason) {
                case DECREASE -> (baseline + signal) / 2.0;
                case HOLD     -> (1 - HOLD_EMA) * baseline + HOLD_EMA * signal;
                case INCREASE -> signal;
            };
            currentTarget.set(next);

            if (next != prev) {
                log.info("pure-vegas {} {} → {} (signal={}, baseline {} → {})",
                        reason.name().toLowerCase(), prev, next,
                        fmt(signal), fmt(prevBaseline), fmt(baseline));
            }
        } finally {
            adjustLock.unlock();
        }
    }

    private static String fmt(double v) { return String.format("%.3f", v); }
}
