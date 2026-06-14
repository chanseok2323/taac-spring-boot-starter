package io.github.chanseok.taac.metrics;

import io.github.chanseok.taac.policy.MemoryPressureLevel;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Aggregated counters and gauges for the admission gate.
 *
 * <p>Cumulative totals use {@link LongAdder} so the hot path doesn't fight
 * over a single counter cell; the live-count gauges stay on
 * {@link AtomicInteger} because they need to be exact.
 */
public class AdmissionMetrics {

    private final LongAdder totalWaitMs   = new LongAdder();
    private final LongAdder totalRequests = new LongAdder();
    private final LongAdder totalTimeouts = new LongAdder();

    private final AtomicInteger currentWaiting = new AtomicInteger();
    private final AtomicInteger peakWaiting    = new AtomicInteger();
    private final AtomicInteger currentActive  = new AtomicInteger();
    private final AtomicInteger peakActive     = new AtomicInteger();

    private final EnumMap<MemoryPressureLevel, LongAdder> pressureDistribution =
            new EnumMap<>(MemoryPressureLevel.class);

    public AdmissionMetrics() {
        for (MemoryPressureLevel level : MemoryPressureLevel.values()) {
            pressureDistribution.put(level, new LongAdder());
        }
    }

    public void recordWaitStart() {
        peakWaiting.updateAndGet(peak -> Math.max(peak, currentWaiting.incrementAndGet()));
    }

    public void recordWaitEnd(long waitMs, MemoryPressureLevel level) {
        currentWaiting.decrementAndGet();
        peakActive.updateAndGet(peak -> Math.max(peak, currentActive.incrementAndGet()));
        totalWaitMs.add(waitMs);
        totalRequests.increment();
        pressureDistribution.get(level).increment();
    }

    public void recordRelease() { currentActive.decrementAndGet(); }

    public void recordTimeout() {
        currentWaiting.decrementAndGet();
        totalTimeouts.increment();
    }

    public MetricsSnapshot snapshot() {
        long requests = totalRequests.sum();
        Map<String, Long> distribution = new LinkedHashMap<>();
        pressureDistribution.forEach((level, count) -> distribution.put(level.name(), count.sum()));

        return new MetricsSnapshot(
                requests,
                requests > 0 ? totalWaitMs.sum() / requests : 0,
                totalTimeouts.sum(),
                currentWaiting.get(),
                peakWaiting.get(),
                currentActive.get(),
                peakActive.get(),
                distribution);
    }

    public record MetricsSnapshot(
            long totalRequests,
            long avgWaitMs,
            long totalTimeouts,
            int currentWaiting,
            int peakWaiting,
            int currentActive,
            int peakActive,
            Map<String, Long> pressureDistribution
    ) {}
}
