package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Textbook TCP AIMD — slow-start exponential growth up to {@code ssthresh},
 * then linear AI ({@code +1}) per evaluation window, and MD ({@code ×0.5})
 * when the average response time worsens by more than 10%. Memory pressure
 * is treated like a Reno congestion event: drop to min, slow-start back up.
 */
public class StandardAimdPolicy implements ConcurrencyPolicy {

    private static final double CONGESTION_RATIO = 1.10;
    private static final double MD_FACTOR        = 0.50;
    private static final int    AI_STEP          = 1;
    private static final int    EVAL_INTERVAL    = 5;

    private final int maxConcurrency;
    private final int minConcurrency;
    private final double moderateHeap;
    private final double highHeap;
    private final double criticalHeap;

    private final AtomicInteger cwnd;
    private volatile int ssthresh;
    private volatile boolean slowStart = true;

    private final AtomicLong completionCount = new AtomicLong();
    private final AtomicLong responseTimeSum = new AtomicLong();
    private volatile long lastEvalCount;
    private volatile long lastAvgRtMs;

    private final ReentrantLock adjustLock = new ReentrantLock();

    public StandardAimdPolicy(int maxConcurrency,
                              int minConcurrency,
                              double moderateHeap,
                              double highHeap,
                              double criticalHeap) {
        this.maxConcurrency = maxConcurrency;
        this.minConcurrency = minConcurrency;
        this.moderateHeap   = moderateHeap;
        this.highHeap       = highHeap;
        this.criticalHeap   = criticalHeap;
        this.cwnd     = new AtomicInteger(minConcurrency);
        this.ssthresh = maxConcurrency;
    }

    @Override
    public void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens) {
        responseTimeSum.addAndGet(responseTimeMs);
        long count = completionCount.incrementAndGet();
        if (count - lastEvalCount >= EVAL_INTERVAL) {
            adjust(count);
        }
    }

    @Override
    public int evaluate(GateState state) {
        if (state.snapshot().usageRatio() >= criticalHeap) {
            int prev = cwnd.get();
            if (prev != minConcurrency) {
                ssthresh = Math.max(minConcurrency, prev / 2);
                cwnd.set(minConcurrency);
                slowStart = true;
            }
            return minConcurrency;
        }
        return cwnd.get();
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        return HeapPressureClassifier.classify(snapshot.usageRatio(), moderateHeap, highHeap, criticalHeap);
    }

    public int currentCwnd() { return cwnd.get(); }

    // --- internals ----------------------------------------------------------

    private void adjust(long count) {
        if (!adjustLock.tryLock()) return;
        try {
            long sinceLast = count - lastEvalCount;
            if (sinceLast < EVAL_INTERVAL) return;

            long totalTime = responseTimeSum.getAndSet(0);
            long avg = sinceLast > 0 ? totalTime / sinceLast : 0;
            int window = cwnd.get();

            if (lastAvgRtMs > 0 && avg > lastAvgRtMs * CONGESTION_RATIO) {
                ssthresh = Math.max(minConcurrency, (int) (window * MD_FACTOR));
                window   = minConcurrency;
                slowStart = true;
            } else if (lastAvgRtMs > 0) {
                window = slowStart
                        ? Math.min(maxConcurrency, window * 2)
                        : Math.min(maxConcurrency, window + AI_STEP);
                if (slowStart && window >= ssthresh) slowStart = false;
            } else {
                // first evaluation — kick off slow-start
                window = Math.min(maxConcurrency, window * 2);
            }

            cwnd.set(window);
            lastAvgRtMs   = avg;
            lastEvalCount = count;
        } finally {
            adjustLock.unlock();
        }
    }
}
