package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Discrete response-time policy. Compares an EMA-smoothed RTT against a
 * baseline established during a 10-sample warmup, and snaps the target to
 * one of four bands. Heap pressure adds a multiplicative attenuation on top.
 *
 * <p>"Drain boost" nudges the target up when the queue is small relative to
 * the current target — keeps the tail of a workload from sitting on a
 * pessimistic permit count after a congestion event clears.
 */
public class ResponseTimeBasedConcurrencyPolicy implements ConcurrencyPolicy {

    private static final Logger log = LoggerFactory.getLogger(ResponseTimeBasedConcurrencyPolicy.class);

    private static final double SLOW_RATIO       = 1.2;
    private static final double DEGRADED_RATIO   = 1.5;
    private static final double OVERLOADED_RATIO = 2.0;

    private final ResponseTimeTracker tracker;
    private final int maxConcurrency;
    private final int minConcurrency;
    private final double moderateHeap;
    private final double highHeap;
    private final double criticalHeap;

    private final int slowTarget;
    private final int degradedTarget;
    private final int moderateHeapTarget;
    private final double heapDecayRangeInv;

    private volatile int    lastLoggedTarget = -1;
    private volatile String lastLoggedReason = "";

    public ResponseTimeBasedConcurrencyPolicy(ResponseTimeTracker tracker,
                                              int maxConcurrency,
                                              int minConcurrency,
                                              double moderateHeap,
                                              double highHeap,
                                              double criticalHeap) {
        this.tracker        = tracker;
        this.maxConcurrency = maxConcurrency;
        this.minConcurrency = minConcurrency;
        this.moderateHeap   = moderateHeap;
        this.highHeap       = highHeap;
        this.criticalHeap   = criticalHeap;

        this.slowTarget         = Math.max(minConcurrency, (int) (maxConcurrency * 0.7));
        this.degradedTarget     = Math.max(minConcurrency, (int) (maxConcurrency * 0.4));
        this.moderateHeapTarget = Math.max(minConcurrency, (int) (maxConcurrency * 0.8));
        this.heapDecayRangeInv  = 1.0 / (criticalHeap - highHeap);
    }

    @Override
    public void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens) {
        tracker.record(responseTimeMs);
    }

    @Override
    public int evaluate(GateState state) {
        double heap = state.snapshot().usageRatio();
        int waiting = state.queueLength();

        if (heap >= criticalHeap) {
            return logTransition(minConcurrency, "heap_critical", heap, -1);
        }

        double ratio = tracker.isWarmedUp() ? tracker.ratioToBaseline() : 0.0;
        int target;
        String reason;
        if (!tracker.isWarmedUp()) {
            target = maxConcurrency;
            reason = "warmup";
        } else if (ratio >= OVERLOADED_RATIO) {
            target = minConcurrency;
            reason = "overloaded";
        } else if (ratio >= DEGRADED_RATIO) {
            target = degradedTarget;
            reason = "degraded";
        } else if (ratio >= SLOW_RATIO) {
            target = slowTarget;
            reason = "slow";
        } else {
            target = maxConcurrency;
            reason = "normal";
        }

        // Drain boost — short queue and we're not at the cap, lift the target a bit.
        if (waiting > 0 && waiting <= target * 2) {
            int boosted = Math.min(maxConcurrency, target + 2);
            if (boosted != target) {
                target = boosted;
                reason += "+drain";
            }
        }

        if (heap >= highHeap) {
            double decay = (heap - highHeap) * heapDecayRangeInv;
            int attenuated = Math.max(minConcurrency, (int) (target * (1.0 - decay * 0.7)));
            return logTransition(attenuated, reason + "+heap_high", heap, ratio);
        }
        if (heap >= moderateHeap) {
            int attenuated = Math.max(minConcurrency,
                    target == maxConcurrency ? moderateHeapTarget : (int) (target * 0.8));
            return logTransition(attenuated, reason + "+heap_moderate", heap, ratio);
        }

        return logTransition(target, reason, heap, ratio);
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        return HeapPressureClassifier.classify(snapshot.usageRatio(), moderateHeap, highHeap, criticalHeap);
    }

    private int logTransition(int target, String reason, double heap, double ratio) {
        if (target == lastLoggedTarget && reason.equals(lastLoggedReason)) {
            return target;
        }
        if (log.isInfoEnabled()) {
            log.info("rt {} → {} reason={} heap={} ratio={}",
                    lastLoggedTarget, target, reason,
                    String.format("%.3f", heap),
                    ratio >= 0 ? String.format("%.3f", ratio) : "-");
        }
        lastLoggedTarget = target;
        lastLoggedReason = reason;
        return target;
    }
}
