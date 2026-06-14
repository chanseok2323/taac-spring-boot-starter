package io.github.chanseok.taac.policy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM 응답 시간을 추적하여 이동 평균과 추세를 제공한다.
 *
 * ── EMA (Exponential Moving Average) ──
 * EMA_new = α × current + (1 - α) × EMA_old
 * α = 0.3 (최근 값에 30% 가중치)
 *
 * ── 워밍업 기반 Baseline ──
 * 처음 10건의 평균으로 baseline을 설정한다.
 * 워밍업 시점은 부하가 적으므로 baseline이 낮게 잡히고,
 * 부하 시 ratio가 높아져 permit이 자연스럽게 축소된다.
 * 이 축소가 GPU 경합을 줄여 오히려 처리량을 높인다.
 */
public class ResponseTimeTracker {

    private static final double ALPHA = 0.3;
    private static final double ONE_MINUS_ALPHA = 1.0 - ALPHA;

    private final AtomicLong emaScaled = new AtomicLong(0);
    private static final long SCALE = 1000L;

    private final AtomicLong baselineMs = new AtomicLong(0);
    private final AtomicInteger count = new AtomicInteger(0);
    private static final int WARMUP_COUNT = 10;
    private final AtomicLong warmupSum = new AtomicLong(0);

    public void record(long responseTimeMs) {
        int n = count.incrementAndGet();
        long newScaled = responseTimeMs * SCALE;

        if (n <= WARMUP_COUNT) {
            warmupSum.addAndGet(responseTimeMs);
            emaScaled.set(newScaled);

            if (n == WARMUP_COUNT) {
                // 워밍업은 부하가 적어 응답이 빠름. 부하 시 자연스러운 증가분을
                // 반영하여 baseline을 3배로 설정 → ratio가 과도하게 높아지는 것 방지
                baselineMs.set((warmupSum.get() / WARMUP_COUNT) * 3);
            }
            return;
        }

        long oldEma;
        long updatedEma;
        do {
            oldEma = emaScaled.get();
            updatedEma = (long) (ALPHA * newScaled + ONE_MINUS_ALPHA * oldEma);
        } while (!emaScaled.compareAndSet(oldEma, updatedEma));
    }

    public double ratioToBaseline() {
        long baseline = baselineMs.get();
        if (baseline <= 0) return 1.0;
        return (double) (emaScaled.get() / SCALE) / baseline;
    }

    public boolean isWarmedUp() {
        return count.get() >= WARMUP_COUNT;
    }

    public long currentEmaMs() {
        return emaScaled.get() / SCALE;
    }

    public int totalCount() {
        return count.get();
    }

    public long baselineMs() {
        return baselineMs.get();
    }
}
