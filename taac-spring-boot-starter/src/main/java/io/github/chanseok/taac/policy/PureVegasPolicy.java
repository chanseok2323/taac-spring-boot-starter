package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Pure Vegas 정책 — Literature 기준 비교군.
 *
 * ── 학술적 위치 ──
 *
 * TCP Vegas (Brakmo &amp; Peterson, 1995) 의 delay-based congestion control 을
 * LLM admission 도메인에 단순 이식한 baseline. 본 논문의 제안 시스템과의
 * head-to-head 비교 대상으로 사용된다.
 *
 * ── 본 논문 제안 시스템({@link TokenAwareVegasPolicy}) 과의 차이 ──
 *
 * 1. **신호**: 토큰 정규화 없음.
 *    Pure Vegas signal = totalTime / sampleCount    (raw 평균 RTT)
 *    Our system signal = totalTime / totalTokens    (token-normalized)
 *
 * 2. **Heap-aware 강등 없음**: Vegas 는 delay-only 정책이므로 memory 신호 통합 없음.
 *
 * ── Vegas 3단 제어는 동일 ──
 *
 * AI / HOLD / MD 의 3단 분기 자체는 TCP Vegas 원형이므로 본 정책에서도 유지한다.
 * Pure Vegas vs Our Vegas 비교는 "동일 알고리즘 골격 + 우리가 추가한 LLM 도메인 적응" 의
 * 효과를 측정하는 구조.
 */
public class PureVegasPolicy implements ConcurrencyPolicy {

    private static final Logger log = LoggerFactory.getLogger(PureVegasPolicy.class);

    private final int maxConcurrency;
    private final int minConcurrency;

    private static final double VEGAS_BETA = 1.1;
    private static final double VEGAS_ALPHA = 0.95;
    private static final double MULTIPLICATIVE_DECREASE = 0.7;
    private static final double ALPHA_HOLD = 0.2;

    private static final int EVAL_INTERVAL = 5;
    private static final int WARMUP_INTERVALS = 3;
    private static final double WARMUP_BUFFER = 2.0;

    private final AtomicLong completionCount = new AtomicLong(0);
    private volatile long lastEvalCount;

    private volatile int warmupSeen = 0;
    private volatile double warmupSum = 0;

    private final AtomicLong responseTimeSum = new AtomicLong(0);
    private volatile double lastSignal;

    private final AtomicInteger currentTarget;
    private final ReentrantLock adjustLock = new ReentrantLock();

    public PureVegasPolicy(int maxConcurrency, int minConcurrency) {
        this.maxConcurrency = maxConcurrency;
        this.minConcurrency = minConcurrency;
        this.currentTarget = new AtomicInteger(maxConcurrency);
        this.lastEvalCount = 0;
        this.lastSignal = 0;
    }

    public void recordCompletion(long responseTimeMs) {
        responseTimeSum.addAndGet(responseTimeMs);
        long count = completionCount.incrementAndGet();
        if (count - lastEvalCount >= EVAL_INTERVAL) {
            adjustTarget();
        }
    }

    @Override
    public void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens) {
        recordCompletion(responseTimeMs);
    }

    private void adjustTarget() {
        if (!adjustLock.tryLock()) return;
        try {
            long totalTime = responseTimeSum.getAndSet(0);
            long sampledCount = completionCount.get();
            long sinceLast = sampledCount - lastEvalCount;
            if (sinceLast < EVAL_INTERVAL) {
                responseTimeSum.addAndGet(totalTime);
                return;
            }

            double signal = sinceLast > 0 ? (double) totalTime / sinceLast : 0;

            if (warmupSeen < WARMUP_INTERVALS) {
                warmupSum += signal;
                warmupSeen++;
                if (warmupSeen == WARMUP_INTERVALS) {
                    lastSignal = (warmupSum / WARMUP_INTERVALS) * WARMUP_BUFFER;
                    if (log.isInfoEnabled()) {
                        log.info("vegas_pure_warmup_done baseline={} samples_avg={}",
                                String.format("%.3f", lastSignal),
                                String.format("%.3f", warmupSum / WARMUP_INTERVALS));
                    }
                }
                lastEvalCount = sampledCount;
                return;
            }

            int prevTarget = currentTarget.get();
            int target = prevTarget;
            String reason = "hold";

            if (lastSignal > 0) {
                if (signal > lastSignal * VEGAS_BETA) {
                    target = Math.max(minConcurrency, (int) (target * MULTIPLICATIVE_DECREASE));
                    reason = "vegas_decrease_congested";
                } else if (signal < lastSignal * VEGAS_ALPHA) {
                    target = Math.min(maxConcurrency, target + 2);
                    reason = "vegas_increase_underutilized";
                } else {
                    reason = "vegas_hold_stable";
                }
            }

            double prevBaseline = lastSignal;
            if ("vegas_decrease_congested".equals(reason)) {
                lastSignal = (lastSignal + signal) / 2.0;
            } else if ("vegas_hold_stable".equals(reason)) {
                lastSignal = (1 - ALPHA_HOLD) * lastSignal + ALPHA_HOLD * signal;
            } else {
                lastSignal = signal;
            }

            currentTarget.set(target);
            lastEvalCount = sampledCount;

            if (target != prevTarget && log.isInfoEnabled()) {
                log.info("vegas_pure_adjust reason={} target={}->{} signal={} prev_baseline={} new_baseline={}",
                        reason, prevTarget, target,
                        String.format("%.3f", signal),
                        String.format("%.3f", prevBaseline),
                        String.format("%.3f", lastSignal));
            }
        } finally {
            adjustLock.unlock();
        }
    }

    @Override
    public int evaluate(GateState state) {
        return currentTarget.get();
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        double heapUsage = snapshot.usageRatio();
        if (heapUsage >= 0.92) return MemoryPressureLevel.CRITICAL;
        if (heapUsage >= 0.85) return MemoryPressureLevel.HIGH;
        if (heapUsage >= 0.70) return MemoryPressureLevel.MODERATE;
        return MemoryPressureLevel.LOW;
    }

    public int currentTarget() {
        return currentTarget.get();
    }
}
