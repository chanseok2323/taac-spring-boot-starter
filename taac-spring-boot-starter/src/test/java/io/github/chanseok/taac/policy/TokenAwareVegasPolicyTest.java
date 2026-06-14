package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenAwareVegasPolicyTest {

    private final MemorySnapshot CALM = new MemorySnapshot(0, 0, 0, 0, 0.10);

    private TokenAwareVegasPolicy newPolicy() {
        return new TokenAwareVegasPolicy(30, 5, 0.70, 0.85, 0.92);
    }

    private ConcurrencyPolicy.GateState state() {
        return new ConcurrencyPolicy.GateState(CALM, 0, 30);
    }

    @Test
    void starts_at_max_concurrency() {
        var policy = newPolicy();
        assertThat(policy.currentTarget()).isEqualTo(30);
        assertThat(policy.evaluate(state())).isEqualTo(30);
    }

    @Test
    void warmup_does_not_change_the_target() {
        var policy = newPolicy();

        // 15 samples × moderate latency — enough to finish warmup and a couple of windows.
        for (int i = 0; i < 15; i++) {
            policy.recordCompletion(100, 100, 100);
        }

        // After warmup, stable signal → HOLD path; target stays at max.
        assertThat(policy.currentTarget()).isEqualTo(30);
    }

    @Test
    void sustained_high_latency_triggers_multiplicative_decrease() {
        var policy = newPolicy();

        // Warmup at modest latency.
        for (int i = 0; i < 15; i++) policy.recordCompletion(100, 100, 100);
        int afterWarmup = policy.currentTarget();

        // Then a window of much higher latency — should drop below max.
        for (int i = 0; i < 25; i++) policy.recordCompletion(2_000, 100, 100);

        assertThat(policy.currentTarget()).isLessThan(afterWarmup);
    }

    @Test
    void critical_heap_clamps_to_min_immediately() {
        var policy = newPolicy();
        var hot = new MemorySnapshot(0, 0, 0, 0, 0.95);  // above 0.92 critical

        int target = policy.evaluate(new ConcurrencyPolicy.GateState(hot, 0, 30));

        assertThat(target).isEqualTo(5);
        assertThat(policy.currentTarget()).isEqualTo(5);
    }

    @Test
    void adaptWeight_collapses_to_one_when_not_congested() {
        var policy = newPolicy();   // currentTarget == max, so isCongested == false

        assertThat(policy.adaptWeight(3)).isOne();
    }

    @Test
    void adaptWeight_preserves_weight_when_congested() {
        var policy = newPolicy();
        // Force the policy into a "congested" state by driving an MD.
        for (int i = 0; i < 15; i++) policy.recordCompletion(100, 100, 100);
        for (int i = 0; i < 25; i++) policy.recordCompletion(3_000, 100, 100);
        assertThat(policy.isCongested()).isTrue();

        assertThat(policy.adaptWeight(3)).isEqualTo(3);
    }

    @Test
    void pressure_level_uses_constructor_thresholds() {
        var policy = newPolicy();

        assertThat(policy.pressureLevel(new MemorySnapshot(0, 0, 0, 0, 0.50)))
                .isEqualTo(MemoryPressureLevel.LOW);
        assertThat(policy.pressureLevel(new MemorySnapshot(0, 0, 0, 0, 0.75)))
                .isEqualTo(MemoryPressureLevel.MODERATE);
        assertThat(policy.pressureLevel(new MemorySnapshot(0, 0, 0, 0, 0.88)))
                .isEqualTo(MemoryPressureLevel.HIGH);
        assertThat(policy.pressureLevel(new MemorySnapshot(0, 0, 0, 0, 0.95)))
                .isEqualTo(MemoryPressureLevel.CRITICAL);
    }
}
