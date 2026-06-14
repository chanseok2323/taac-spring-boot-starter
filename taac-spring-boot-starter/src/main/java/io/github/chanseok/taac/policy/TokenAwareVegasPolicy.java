package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Token-aware delay-based 동시성 정책 (TCP Vegas 영감) — TAAC의 핵심 정책.
 *
 * ── TCP Vegas 계보 ──
 *
 * Reno/AIMD는 "패킷 손실"을 혼잡 신호로 쓰지만, LLM serving은 요청을 drop하지 않고
 * 지연시키므로 loss-based 신호가 부자연스럽다. TCP Vegas (Brakmo &amp; Peterson, 1995)는
 * RTT 증가 자체를 혼잡 예측 신호로 사용하며, Reno보다 선제적이고 oscillation이 적다.
 *
 * 본 정책은 Vegas의 delay-based 접근을 LLM admission control에 적용하고,
 * 두 가지 확장을 추가했다:
 *
 * 1. Token normalization : totalTime / totalInputTokens — 요청 크기 정규화 지연
 *    Vegas의 RTT는 크기 독립적이지만 LLM latency는 토큰 수에 강하게 의존.
 *    "GPU 경합으로 인한 지연"과 "큰 요청으로 인한 지연"을 구분한다.
 * 2. Fast Decay phase : 초기 탐색 단계에서 지수 감소(×0.5)로 빠르게 최적점 수렴.
 *    이후 Vegas 3단 제어로 안정화.
 *
 * ── Vegas 3단 제어 (AIMD와 다른 핵심) ──
 *
 * AIMD는 2단: 혼잡 감지 시 감소, 그 외 항상 증가 (지속 oscillation).
 * Vegas는 3단: 감소 / **유지** / 증가 — 안정 구간의 발진을 방지한다.
 *
 * normalizedLatency &gt; last × β (1.1) → Multiplicative Decrease (×0.7) [혼잡]
 * last × α (0.95) ≤ L ≤ last × β     → HOLD [안정 band]
 * normalizedLatency &lt; last × α       → Additive Increase (+1) [여유]
 *
 * ── New Normal 병리 방지 ──
 *
 * MD 발생 시 baseline을 (old + new) / 2로 스무딩하여, 악화된 값을 즉시 표준으로
 * 수용하지 않는다. queue가 해소될 때까지 재증가 폭주를 차단한다.
 *
 * ── 힙 critical 강등 ──
 *
 * 힙 사용률이 critical을 넘으면 latency 신호 대기 없이 즉시 minConcurrency로 강등.
 * Vegas가 순수 delay-only인 것과 달리, memory signal을 2차 혼잡 신호로 통합한다.
 */
public class TokenAwareVegasPolicy implements ConcurrencyPolicy {

    private static final Logger log = LoggerFactory.getLogger(TokenAwareVegasPolicy.class);

    private final int maxConcurrency;
    private final int minConcurrency;
    private final double criticalHeapThreshold;

    private static final double VEGAS_BETA = 1.1;
    private static final double VEGAS_ALPHA = 0.95;
    private static final double MULTIPLICATIVE_DECREASE = 0.7;
    private static final double ALPHA_HOLD = 0.2;

    private static final int EVAL_INTERVAL = 5;
    private static final int FAST_DECAY_INTERVAL = 3;
    private static final int WARMUP_INTERVALS = 3;
    private static final double WARMUP_BUFFER = 2.0;

    private volatile boolean fastDecayPhase = false;
    private volatile int preDecayTarget;

    private final AtomicLong completionCount = new AtomicLong(0);
    private volatile long lastEvalCount;

    private volatile int warmupSeen = 0;
    private volatile double warmupSum = 0;

    private final AtomicLong responseTimeSum = new AtomicLong(0);
    private final AtomicLong inputTokenSum = new AtomicLong(0);
    private volatile double lastNormalizedLatency;

    private final AtomicInteger currentTarget;
    private final ReentrantLock adjustLock = new ReentrantLock();

    public TokenAwareVegasPolicy(int maxConcurrency, int minConcurrency, double criticalHeapThreshold) {
        this.maxConcurrency = maxConcurrency;
        this.minConcurrency = minConcurrency;
        this.criticalHeapThreshold = criticalHeapThreshold;
        this.currentTarget = new AtomicInteger(maxConcurrency);
        this.preDecayTarget = maxConcurrency;
        this.lastEvalCount = 0;
        this.lastNormalizedLatency = 0;
    }

    /**
     * Token-aware 완료 기록.
     * 응답시간 + 입력/출력 토큰 수 → 총 토큰(입력+출력)당 latency로 정규화.
     */
    @Override
    public void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens) {
        if (inputTokens < 1) inputTokens = 1;
        if (outputTokens < 0) outputTokens = 0;
        int totalTokens = inputTokens + outputTokens;

        responseTimeSum.addAndGet(responseTimeMs);
        inputTokenSum.addAndGet(totalTokens);
        long count = completionCount.incrementAndGet();

        int interval = fastDecayPhase ? FAST_DECAY_INTERVAL : EVAL_INTERVAL;
        if (count - lastEvalCount >= interval) {
            adjustTarget();
        }
    }

    public void recordCompletion(long responseTimeMs, int inputTokens) {
        recordCompletion(responseTimeMs, inputTokens, 0);
    }

    public void recordCompletion(long responseTimeMs) {
        recordCompletion(responseTimeMs, 1, 0);
    }

    private void adjustTarget() {
        if (!adjustLock.tryLock()) return;
        try {
            long totalTime = responseTimeSum.getAndSet(0);
            long totalTokens = inputTokenSum.getAndSet(0);
            long sampledCount = completionCount.get();

            long sinceLast = sampledCount - lastEvalCount;
            int interval = fastDecayPhase ? FAST_DECAY_INTERVAL : EVAL_INTERVAL;
            if (sinceLast < interval) {
                responseTimeSum.addAndGet(totalTime);
                inputTokenSum.addAndGet(totalTokens);
                return;
            }

            double normalizedLatency = totalTokens > 0
                    ? (double) totalTime / totalTokens
                    : 0;

            if (warmupSeen < WARMUP_INTERVALS) {
                warmupSum += normalizedLatency;
                warmupSeen++;
                if (warmupSeen == WARMUP_INTERVALS) {
                    lastNormalizedLatency = (warmupSum / WARMUP_INTERVALS) * WARMUP_BUFFER;
                    if (log.isInfoEnabled()) {
                        log.info("vegas_warmup_done baseline={} samples_avg={}",
                                String.format("%.3f", lastNormalizedLatency),
                                String.format("%.3f", warmupSum / WARMUP_INTERVALS));
                    }
                }
                lastEvalCount = sampledCount;
                return;
            }

            int prevTarget = currentTarget.get();
            int target = prevTarget;
            String reason = "hold";

            if (fastDecayPhase) {
                if (lastNormalizedLatency == 0) {
                    preDecayTarget = target;
                    target = Math.max(minConcurrency, target / 2);
                    reason = "fast_decay_init";
                } else if (normalizedLatency < lastNormalizedLatency * 0.95) {
                    preDecayTarget = target;
                    target = Math.max(minConcurrency, target / 2);
                    reason = "fast_decay_improved";
                } else if (normalizedLatency > lastNormalizedLatency * 1.05) {
                    target = preDecayTarget;
                    fastDecayPhase = false;
                    reason = "fast_decay_revert_to_vegas";
                } else {
                    fastDecayPhase = false;
                    reason = "fast_decay_exit_to_vegas";
                }
            } else {
                if (lastNormalizedLatency > 0) {
                    if (normalizedLatency > lastNormalizedLatency * VEGAS_BETA) {
                        target = Math.max(minConcurrency, (int) (target * MULTIPLICATIVE_DECREASE));
                        reason = "vegas_decrease_congested";
                    } else if (normalizedLatency < lastNormalizedLatency * VEGAS_ALPHA) {
                        target = Math.min(maxConcurrency, target + 2);
                        reason = "vegas_increase_underutilized";
                    } else {
                        reason = "vegas_hold_stable";
                    }
                }
            }

            double prevBaseline = lastNormalizedLatency;
            if ("vegas_decrease_congested".equals(reason)) {
                lastNormalizedLatency = (lastNormalizedLatency + normalizedLatency) / 2.0;
            } else if ("vegas_hold_stable".equals(reason)) {
                lastNormalizedLatency = (1 - ALPHA_HOLD) * lastNormalizedLatency
                                      + ALPHA_HOLD * normalizedLatency;
            } else {
                lastNormalizedLatency = normalizedLatency;
            }

            currentTarget.set(target);
            lastEvalCount = sampledCount;

            if (target != prevTarget && log.isInfoEnabled()) {
                log.info("vegas_adjust reason={} target={}->{} norm_latency={} prev_baseline={} new_baseline={} phase={}",
                        reason, prevTarget, target,
                        String.format("%.3f", normalizedLatency),
                        String.format("%.3f", prevBaseline),
                        String.format("%.3f", lastNormalizedLatency),
                        fastDecayPhase ? "fast_decay" : "vegas");
            }
        } finally {
            adjustLock.unlock();
        }
    }

    @Override
    public int evaluate(GateState state) {
        MemorySnapshot snapshot = state.snapshot();
        if (snapshot.usageRatio() >= criticalHeapThreshold) {
            if (log.isWarnEnabled() && currentTarget.get() != minConcurrency) {
                log.warn("vegas_heap_override target->{} heap={} threshold={}",
                        minConcurrency,
                        String.format("%.3f", snapshot.usageRatio()),
                        String.format("%.3f", criticalHeapThreshold));
            }
            fastDecayPhase = false;
            currentTarget.set(minConcurrency);
            lastNormalizedLatency = 0;
            warmupSeen = 0;
            warmupSum = 0;
            return minConcurrency;
        }
        return currentTarget.get();
    }

    @Override
    public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) {
        double heapUsage = snapshot.usageRatio();
        if (heapUsage >= criticalHeapThreshold) return MemoryPressureLevel.CRITICAL;
        if (heapUsage >= 0.85) return MemoryPressureLevel.HIGH;
        if (heapUsage >= 0.70) return MemoryPressureLevel.MODERATE;
        return MemoryPressureLevel.LOW;
    }

    public int currentTarget() {
        return currentTarget.get();
    }

    /**
     * 정책이 현재 backend 혼잡을 감지한 상태인지.
     * currentTarget &lt; maxConcurrency 이면 최근 MD가 발동했거나 회복 중 → 혼잡으로 해석.
     */
    public boolean isCongested() {
        return currentTarget.get() < maxConcurrency;
    }

    /**
     * Adaptive weight collapse — backend 미혼잡 시 weight=1 로 강등.
     *
     * <p>이전에는 컨트롤러가 정책 상태를 알고 있어야 했지만, 이제는 정책이 스스로
     * 자신의 혼잡 판단을 weight 결정에 반영한다 — 컨트롤러는 정책 종류를 알 필요 없음.
     */
    @Override
    public int adaptWeight(int requestedWeight) {
        return isCongested() ? requestedWeight : 1;
    }
}
