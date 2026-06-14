package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StandardAimdPolicyTest {

    private final MemorySnapshot CALM = new MemorySnapshot(0, 0, 0, 0, 0.10);

    private StandardAimdPolicy newPolicy() {
        return new StandardAimdPolicy(20, 2, 0.70, 0.85, 0.92);
    }

    private ConcurrencyPolicy.GateState state() {
        return new ConcurrencyPolicy.GateState(CALM, 0, 20);
    }

    @Test
    void starts_at_min_concurrency() {
        var policy = newPolicy();
        assertThat(policy.currentCwnd()).isEqualTo(2);
    }

    @Test
    void steady_low_latency_drives_slow_start_growth() {
        var policy = newPolicy();
        int initial = policy.currentCwnd();

        // 25 stable samples — five evaluation windows of slow-start doubling.
        for (int i = 0; i < 25; i++) policy.recordCompletion(50, 0, 0);
        policy.evaluate(state());   // not needed for cwnd, but mimics the real loop

        assertThat(policy.currentCwnd()).isGreaterThan(initial);
    }

    @Test
    void worsening_latency_triggers_md_and_drops_to_min() {
        var policy = newPolicy();

        // Drive cwnd up first.
        for (int i = 0; i < 25; i++) policy.recordCompletion(50, 0, 0);
        int afterGrowth = policy.currentCwnd();
        assertThat(afterGrowth).isGreaterThan(2);

        // Then a window of much higher latency — congestion event.
        for (int i = 0; i < 10; i++) policy.recordCompletion(500, 0, 0);

        assertThat(policy.currentCwnd()).isLessThan(afterGrowth);
    }

    @Test
    void critical_heap_acts_like_a_congestion_event() {
        var policy = newPolicy();
        for (int i = 0; i < 25; i++) policy.recordCompletion(50, 0, 0);
        var hot = new MemorySnapshot(0, 0, 0, 0, 0.95);

        int target = policy.evaluate(new ConcurrencyPolicy.GateState(hot, 0, 20));

        assertThat(target).isEqualTo(2);
        assertThat(policy.currentCwnd()).isEqualTo(2);
    }
}
