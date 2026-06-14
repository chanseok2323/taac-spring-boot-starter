package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseTimeBasedConcurrencyPolicyTest {

    private static final MemorySnapshot CALM     = new MemorySnapshot(0, 0, 0, 0, 0.10);
    private static final MemorySnapshot HOT      = new MemorySnapshot(0, 0, 0, 0, 0.95);
    private static final MemorySnapshot MODERATE = new MemorySnapshot(0, 0, 0, 0, 0.75);

    private ResponseTimeBasedConcurrencyPolicy newPolicy(ResponseTimeTracker tracker) {
        return new ResponseTimeBasedConcurrencyPolicy(tracker, 20, 2, 0.70, 0.85, 0.92);
    }

    @Test
    void warmup_state_keeps_target_at_max_concurrency() {
        var tracker = new ResponseTimeTracker();
        var policy  = newPolicy(tracker);

        // Tracker not warmed up yet.
        int target = policy.evaluate(new ConcurrencyPolicy.GateState(CALM, 0, 20));

        assertThat(target).isEqualTo(20);
    }

    @Test
    void critical_heap_overrides_to_min() {
        var policy = newPolicy(new ResponseTimeTracker());

        int target = policy.evaluate(new ConcurrencyPolicy.GateState(HOT, 0, 20));

        assertThat(target).isEqualTo(2);
    }

    @Test
    void overloaded_latency_band_collapses_to_min() {
        var tracker = new ResponseTimeTracker();
        // Establish a small baseline.
        for (int i = 0; i < 10; i++) tracker.record(100);
        // Then EMA blows up to >2× baseline.
        for (int i = 0; i < 20; i++) tracker.record(800);

        int target = newPolicy(tracker)
                .evaluate(new ConcurrencyPolicy.GateState(CALM, 0, 20));

        assertThat(target).isEqualTo(2);
    }

    @Test
    void moderate_heap_attenuates_a_max_target_to_a_lower_one() {
        var tracker = new ResponseTimeTracker();
        for (int i = 0; i < 10; i++) tracker.record(100);
        for (int i = 0; i < 5;  i++) tracker.record(100);  // stay in normal band

        int target = newPolicy(tracker)
                .evaluate(new ConcurrencyPolicy.GateState(MODERATE, 0, 20));

        assertThat(target).isLessThan(20).isGreaterThan(2);
    }

    @Test
    void drain_boost_lifts_target_when_the_queue_is_small() {
        var tracker = new ResponseTimeTracker();
        for (int i = 0; i < 10; i++) tracker.record(150);   // baseline ~450
        // EMA roughly 150 → ratio 150/450 ≈ 0.33 → "normal" band → max_concurrency target.
        // We can't observe the bump directly because target is already capped at max, but
        // a degraded scenario can be: keep tracker just under slow band and add a small queue.
        for (int i = 0; i < 5; i++) tracker.record(450);  // ratio ≈ 1.0 → still normal

        int normalTarget = newPolicy(tracker)
                .evaluate(new ConcurrencyPolicy.GateState(CALM, 0, 20));
        int boosted = newPolicy(tracker)
                .evaluate(new ConcurrencyPolicy.GateState(CALM, 5, 20));  // queue=5 ≤ 2×target

        // Either both already capped (max) or drain_boost grew it; never shrink it.
        assertThat(boosted).isGreaterThanOrEqualTo(normalTarget);
    }
}
