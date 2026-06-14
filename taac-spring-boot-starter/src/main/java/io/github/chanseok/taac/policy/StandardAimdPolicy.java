package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 표준 TCP AIMD 정책 (비교 실험용, 원형 재현).
 *
 * ── TCP Congestion Control 원형 ──
 *
 * Phase 1 - Slow Start:
 *   초기 cwnd = 1부터 시작.
 *   ACK(응답 완료) 마다 cwnd × 2 (지수 증가)
 *   ssthresh(slow start threshold) 도달 시 Congestion Avoidance 전환
 *
 * Phase 2 - Congestion Avoidance (AI):
 *   RTT(본 구현에서는 요청 완료 건수) 마다 cwnd += 1
 *
 * Phase 3 - Congestion Detection (MD):
 *   혼잡 감지 시 (응답시간 악화) cwnd × 0.5
 *   ssthresh = cwnd / 2로 재설정
 *   cwnd = 1로 초기화하고 Slow Start 재진입
 *
 * ── 본 구현의 적응 ──
 *
 * TCP는 패킷 손실을 혼잡 신호로 사용하지만,
 * 본 구현에서는 응답시간 악화(>10%)를 혼잡 신호로 사용한다.
 * 나머지는 TCP 원형 그대로:
 *   - Slow Start 있음
 *   - AI step = 1
 *   - MD factor = 0.5 (TCP 표준)
 *   - Token-aware 없음 (단순 평균 응답시간)
 */
public class StandardAimdPolicy implements ConcurrencyPolicy {

    private final int maxConcurrency;
    private final int minConcurrency;
    private final double criticalHeapThreshold;

    private static final double MD_FACTOR = 0.5;
    private static final int AI_STEP = 1;
    private static final int EVAL_INTERVAL = 5;

    private volatile boolean slowStartPhase = true;
    private volatile int ssthresh;

    private final AtomicLong completionCount = new AtomicLong(0);
    private volatile long lastEvalCount;
    private final AtomicLong responseTimeSum = new AtomicLong(0);
    private volatile long lastAvgResponseTime;

    private final AtomicInteger cwnd;
    private final ReentrantLock adjustLock = new ReentrantLock();

    public StandardAimdPolicy(int maxConcurrency, int minConcurrency, double criticalHeapThreshold) {
        this.maxConcurrency = maxConcurrency;
        this.minConcurrency = minConcurrency;
        this.criticalHeapThreshold = criticalHeapThreshold;
        this.cwnd = new AtomicInteger(minConcurrency);
        this.ssthresh = maxConcurrency;
        this.lastEvalCount = 0;
        this.lastAvgResponseTime = 0;
    }

    public void recordCompletion(long responseTimeMs) {
        responseTimeSum.addAndGet(responseTimeMs);
        long count = completionCount.incrementAndGet();

        if (count - lastEvalCount >= EVAL_INTERVAL) {
            adjust(count);
        }
    }

    @Override
    public void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens) {
        // TCP AIMD 는 토큰을 무시한다 — 원형 재현 목적.
        recordCompletion(responseTimeMs);
    }

    private void adjust(long count) {
        if (!adjustLock.tryLock()) return;
        try {
            long sinceLast = count - lastEvalCount;
            if (sinceLast < EVAL_INTERVAL) return;

            long totalTime = responseTimeSum.getAndSet(0);
            long avgResponseTime = sinceLast > 0 ? totalTime / sinceLast : 0;

            int window = cwnd.get();

            if (lastAvgResponseTime > 0 && avgResponseTime > lastAvgResponseTime * 1.1) {
                ssthresh = Math.max(minConcurrency, (int) (window * MD_FACTOR));
                window = Math.max(minConcurrency, minConcurrency);
                slowStartPhase = true;
            } else if (lastAvgResponseTime > 0) {
                if (slowStartPhase) {
                    window = Math.min(maxConcurrency, window * 2);
                    if (window >= ssthresh) {
                        slowStartPhase = false;
                    }
                } else {
                    window = Math.min(maxConcurrency, window + AI_STEP);
                }
            } else {
                window = Math.min(maxConcurrency, window * 2);
            }

            cwnd.set(window);
            lastAvgResponseTime = avgResponseTime;
            lastEvalCount = count;
        } finally {
            adjustLock.unlock();
        }
    }

    @Override
    public int evaluate(GateState state) {
        MemorySnapshot snapshot = state.snapshot();
        if (snapshot.usageRatio() >= criticalHeapThreshold) {
            int prev = cwnd.get();
            if (prev != minConcurrency) {
                ssthresh = Math.max(minConcurrency, prev / 2);
                cwnd.set(minConcurrency);
                slowStartPhase = true;
            }
            return minConcurrency;
        }
        return cwnd.get();
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        double heapUsage = snapshot.usageRatio();
        if (heapUsage >= criticalHeapThreshold) return MemoryPressureLevel.CRITICAL;
        if (heapUsage >= 0.85) return MemoryPressureLevel.HIGH;
        if (heapUsage >= 0.70) return MemoryPressureLevel.MODERATE;
        return MemoryPressureLevel.LOW;
    }

    public int currentCwnd() {
        return cwnd.get();
    }
}
