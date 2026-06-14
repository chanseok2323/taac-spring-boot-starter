package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Delay-based admission control, adapted from TCP Vegas
 * (Brakmo &amp; Peterson, 1995) for LLM serving. Two adaptations:
 *
 * <ul>
 *   <li>Normalises latency by total token count, so long prompts and GPU
 *       contention produce distinct signals.</li>
 *   <li>Drops to {@code minConcurrency} immediately when heap usage crosses
 *       the critical threshold — Vegas itself doesn't observe memory.</li>
 * </ul>
 *
 * <p>Three-state controller (AI / HOLD / MD) over a sliding baseline EMA.
 * Baseline update rule depends on the move: MD blends old and new equally
 * to damp oscillation, HOLD tracks slowly (α=0.2), AI follows the new value.
 */
public class TokenAwareVegasPolicy implements ConcurrencyPolicy {

    private static final Logger log = LoggerFactory.getLogger(TokenAwareVegasPolicy.class);

    private static final double BETA  = 1.10;   // signal > baseline × β  → decrease
    private static final double ALPHA = 0.95;   // signal < baseline × α  → increase
    private static final double MD_FACTOR = 0.70;
    private static final double HOLD_EMA  = 0.20;

    private static final int EVAL_INTERVAL   = 5;
    private static final int WARMUP_SAMPLES  = 3;
    /** Bumps the warmup baseline so a stable load doesn't trip MD on the first samples. */
    private static final double WARMUP_BUFFER = 2.0;

    private final int maxConcurrency;
    private final int minConcurrency;
    private final double moderateHeap;
    private final double highHeap;
    private final double criticalHeap;

    private final AtomicLong responseTimeSum = new AtomicLong();
    private final AtomicLong totalTokenSum   = new AtomicLong();
    private final AtomicLong completionCount = new AtomicLong();
    private volatile long lastEvalCount;

    private volatile int    warmupSeen;
    private volatile double warmupSum;
    /** Sliding latency baseline. Starts 0 and stays 0 until warmup finishes. */
    private volatile double baseline;

    private final AtomicInteger currentTarget;
    private final ReentrantLock adjustLock = new ReentrantLock();

    public TokenAwareVegasPolicy(int maxConcurrency,
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
        int in  = Math.max(1, inputTokens);
        int out = Math.max(0, outputTokens);

        responseTimeSum.addAndGet(responseTimeMs);
        totalTokenSum.addAndGet((long) in + out);
        long count = completionCount.incrementAndGet();

        if (count - lastEvalCount >= EVAL_INTERVAL) {
            adjustTarget();
        }
    }

    @Override
    public int evaluate(GateState state) {
        MemorySnapshot snapshot = state.snapshot();
        if (snapshot.usageRatio() >= criticalHeap) {
            if (currentTarget.get() != minConcurrency) {
                log.warn("heap override → min={} (heap={}, threshold={})",
                        minConcurrency, fmt(snapshot.usageRatio()), fmt(criticalHeap));
            }
            currentTarget.set(minConcurrency);
            // Throw away baseline state — when the heap is in trouble, what we
            // measured before isn't a useful reference for the recovery path.
            baseline = 0;
            warmupSeen = 0;
            warmupSum = 0;
            return minConcurrency;
        }
        return currentTarget.get();
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        return HeapPressureClassifier.classify(snapshot.usageRatio(), moderateHeap, highHeap, criticalHeap);
    }

    @Override
    public int adaptWeight(int requestedWeight) {
        // While the backend looks idle, paying the full weight just buys queue
        // delay we don't need — collapse to 1 and rely on the gate to throttle
        // again the moment MD fires.
        return isCongested() ? requestedWeight : 1;
    }

    public int currentTarget() { return currentTarget.get(); }

    /** True when MD has fired (or AI hasn't fully recovered) since the last steady period. */
    public boolean isCongested() { return currentTarget.get() < maxConcurrency; }

    // --- internals ----------------------------------------------------------

    private enum Reason { DECREASE, HOLD, INCREASE }

    private void adjustTarget() {
        if (!adjustLock.tryLock()) return;
        try {
            long totalTime  = responseTimeSum.getAndSet(0);
            long totalToks  = totalTokenSum.getAndSet(0);
            long sampled    = completionCount.get();
            long sinceLast  = sampled - lastEvalCount;
            if (sinceLast < EVAL_INTERVAL) {
                // Lost a race with another adjust; put samples back and bail.
                responseTimeSum.addAndGet(totalTime);
                totalTokenSum.addAndGet(totalToks);
                return;
            }
            lastEvalCount = sampled;

            double signal = totalToks > 0 ? (double) totalTime / totalToks : 0;

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
                log.info("vegas {} {} → {} (signal={}, baseline {} → {})",
                        reason.name().toLowerCase(), prev, next,
                        fmt(signal), fmt(prevBaseline), fmt(baseline));
            }
        } finally {
            adjustLock.unlock();
        }
    }

    private static String fmt(double v) { return String.format("%.3f", v); }
}
