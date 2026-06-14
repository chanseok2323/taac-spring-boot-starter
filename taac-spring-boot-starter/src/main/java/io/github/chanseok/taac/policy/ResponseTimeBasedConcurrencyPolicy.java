package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * 응답 시간 기반 적응형 동시성 정책.
 *
 * ── 동작 원리 ──
 *
 * 1. 워밍업(10건) 후 baseline 설정 → 부하 시 ratio 상승 → permit 축소
 *    → GPU 경합 감소 → 요청당 응답 빨라짐 → 전체 처리량 증가
 *
 * 2. 대기 큐가 줄어들면 permit 복구 (Drain Boost)
 *    → 마지막 요청들이 낮은 permit에 갇히는 문제 방지
 *    → 대기자 수 × 2 이하로 permit을 올려 빠르게 소진
 *
 * ── ratio 임계치 ──
 *
 *   ratio < 1.2  → maxConcurrency
 *   ratio 1.2~1.5 → 70%
 *   ratio 1.5~2.0 → 40%
 *   ratio > 2.0  → minConcurrency
 */
public class ResponseTimeBasedConcurrencyPolicy implements ConcurrencyPolicy {

    private static final Logger log = LoggerFactory.getLogger(ResponseTimeBasedConcurrencyPolicy.class);

    private final int maxConcurrency;
    private final int minConcurrency;
    private final double criticalHeapThreshold;
    private final double highHeapThreshold;
    private final double moderateHeapThreshold;
    private final ResponseTimeTracker tracker;

    private final int slowTarget;
    private final int degradedTarget;
    private final int moderateHeapTarget;
    private final double heapDecayRangeInv;

    private static final double SLOW_RATIO = 1.2;
    private static final double DEGRADED_RATIO = 1.5;
    private static final double OVERLOADED_RATIO = 2.0;

    private volatile int lastLoggedTarget = -1;
    private volatile String lastLoggedReason = "";

    public ResponseTimeBasedConcurrencyPolicy(
            ResponseTimeTracker tracker,
            int maxConcurrency,
            int minConcurrency,
            double moderateHeapThreshold,
            double highHeapThreshold,
            double criticalHeapThreshold) {
        this.tracker = tracker;
        this.maxConcurrency = maxConcurrency;
        this.minConcurrency = minConcurrency;
        this.moderateHeapThreshold = moderateHeapThreshold;
        this.highHeapThreshold = highHeapThreshold;
        this.criticalHeapThreshold = criticalHeapThreshold;

        this.slowTarget = Math.max(minConcurrency, (int) (maxConcurrency * 0.7));
        this.degradedTarget = Math.max(minConcurrency, (int) (maxConcurrency * 0.4));
        this.moderateHeapTarget = Math.max(minConcurrency, (int) (maxConcurrency * 0.8));
        this.heapDecayRangeInv = 1.0 / (criticalHeapThreshold - highHeapThreshold);
    }

    @Override
    public void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens) {
        tracker.record(responseTimeMs);
    }

    @Override
    public int evaluate(GateState state) {
        MemorySnapshot snapshot = state.snapshot();
        int waiting = state.queueLength();
        double heapUsage = snapshot.usageRatio();

        if (heapUsage >= criticalHeapThreshold) {
            return logTransition(minConcurrency, "heap_critical", heapUsage, -1);
        }

        int target;
        String reason;
        double ratio = tracker.isWarmedUp() ? tracker.ratioToBaseline() : 0.0;
        if (!tracker.isWarmedUp()) {
            target = maxConcurrency;
            reason = "warmup";
        } else if (ratio >= OVERLOADED_RATIO) {
            target = minConcurrency;
            reason = "latency_overloaded";
        } else if (ratio >= DEGRADED_RATIO) {
            target = degradedTarget;
            reason = "latency_degraded";
        } else if (ratio >= SLOW_RATIO) {
            target = slowTarget;
            reason = "latency_slow";
        } else {
            target = maxConcurrency;
            reason = "latency_normal";
        }

        if (waiting > 0 && waiting <= target * 2) {
            int boosted = Math.min(maxConcurrency, target + 2);
            if (boosted != target) {
                target = boosted;
                reason = reason + "+drain_boost";
            }
        }

        if (heapUsage >= highHeapThreshold) {
            double decay = (heapUsage - highHeapThreshold) * heapDecayRangeInv;
            int reduced = Math.max(minConcurrency, (int) (target * (1.0 - decay * 0.7)));
            return logTransition(reduced, reason + "+heap_high_decay", heapUsage, ratio);
        }
        if (heapUsage >= moderateHeapThreshold) {
            int reduced = Math.max(minConcurrency, (target == maxConcurrency)
                    ? moderateHeapTarget
                    : (int) (target * 0.8));
            return logTransition(reduced, reason + "+heap_moderate", heapUsage, ratio);
        }

        return logTransition(target, reason, heapUsage, ratio);
    }

    private int logTransition(int target, String reason, double heapUsage, double ratio) {
        if (target != lastLoggedTarget || !reason.equals(lastLoggedReason)) {
            if (log.isInfoEnabled()) {
                log.info("rt_policy target={}->{} reason={} heap={} ratio={}",
                        lastLoggedTarget, target, reason,
                        String.format("%.3f", heapUsage),
                        ratio >= 0 ? String.format("%.3f", ratio) : "-");
            }
            lastLoggedTarget = target;
            lastLoggedReason = reason;
        }
        return target;
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        double heapUsage = snapshot.usageRatio();
        if (heapUsage >= criticalHeapThreshold) return MemoryPressureLevel.CRITICAL;
        if (heapUsage >= highHeapThreshold) return MemoryPressureLevel.HIGH;
        if (heapUsage >= moderateHeapThreshold) return MemoryPressureLevel.MODERATE;
        return MemoryPressureLevel.LOW;
    }
}
