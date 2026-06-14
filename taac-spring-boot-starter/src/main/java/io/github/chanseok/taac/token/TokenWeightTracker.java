package io.github.chanseok.taac.token;

import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link WeightStrategy} 의 EMA 기반 기본 구현체.
 *
 * <p>요청 토큰 수의 이동 평균을 추적하여 permit 가중치(weight)를 산출한다.
 *
 * ── 가중치 정의 ──
 *
 *   weight(r) = ceil( tokens(r) / avgTokens )
 *
 *   - 평균 수준 요청: weight = 1  (permit 1개 소비)
 *   - 평균 2배 요청: weight = 2  (permit 2개 소비)
 *   - 평균 3배 요청: weight = 3  (permit 3개 소비)
 *
 * ── 평균 갱신 (EMA) ──
 *
 *   avgTokens_new = α · tokens + (1 - α) · avgTokens_old
 *   α = 0.1  (천천히 적응)
 *
 * ── 초기값 ──
 *
 * 시작값 = defaultAvgTokens (taac.token.default-avg).
 * 첫 record 도 일반 EMA 식 그대로 적용 — 첫 측정값에 박지 않음.
 * 이유: 첫 요청이 비정상적으로 작거나 크면 EMA 가 그 값에 고착되어
 *       이후 요청 weight 가 모두 왜곡됨. EMA 본연의 부드러운 적응으로 회피.
 *
 * ── Weight 상한 ──
 *
 * 한 요청이 지나치게 많은 permit을 점유하지 않도록 maxWeight로 제한.
 */
public class TokenWeightTracker implements WeightStrategy {

    private static final double ALPHA = 0.1;
    private static final double ONE_MINUS_ALPHA = 1.0 - ALPHA;
    private static final long SCALE = 1000L;

    private final int defaultAvgTokens;
    private final int maxWeight;

    /** 평균 토큰 수 (스케일링된 EMA) */
    private volatile long avgTokensScaled;
    private final AtomicLong recordCount = new AtomicLong(0);

    public TokenWeightTracker(int defaultAvgTokens, int maxWeight) {
        this.defaultAvgTokens = defaultAvgTokens;
        this.maxWeight = maxWeight;
        this.avgTokensScaled = (long) defaultAvgTokens * SCALE;
    }

    /** {@link WeightStrategy#weightFor} 의 구현. */
    @Override
    public int weightFor(int inputTokens) {
        return computeWeight(inputTokens);
    }

    /** {@link WeightStrategy#recordInput} 의 구현 — 내부 EMA 를 갱신. */
    @Override
    public void recordInput(int inputTokens) {
        record(inputTokens);
    }

    /** 요청의 토큰 수로부터 permit weight를 산출한다. */
    public int computeWeight(int tokens) {
        long avg = avgTokensScaled / SCALE;
        if (avg <= 0) avg = defaultAvgTokens;

        int weight = (int) Math.ceil((double) tokens / avg);
        return Math.min(maxWeight, Math.max(1, weight));
    }

    /** 요청 완료 시 토큰 수를 기록하여 평균을 갱신한다. */
    public void record(int tokens) {
        if (tokens <= 0) return;

        recordCount.incrementAndGet();
        long scaled = (long) tokens * SCALE;

        long old = avgTokensScaled;
        avgTokensScaled = (long) (ALPHA * scaled + ONE_MINUS_ALPHA * old);
    }

    public int currentAvgTokens() {
        return (int) (avgTokensScaled / SCALE);
    }

    @Override
    public int maxWeight() {
        return maxWeight;
    }
}
