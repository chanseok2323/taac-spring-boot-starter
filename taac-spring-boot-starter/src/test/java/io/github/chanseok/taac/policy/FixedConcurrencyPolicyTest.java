package io.github.chanseok.taac.policy;

import io.github.chanseok.taac.memory.MemorySnapshot;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FixedConcurrencyPolicyTest {

    @Test
    void evaluate_always_returns_max_concurrency_regardless_of_state() {
        var policy = new FixedConcurrencyPolicy(15);
        var snapshot = new MemorySnapshot(0, 0, 0, 0, 0.99);  // heap basically full

        int target = policy.evaluate(new ConcurrencyPolicy.GateState(snapshot, 50, 5));

        assertThat(target).isEqualTo(15);
    }

    @Test
    void pressure_level_is_low_no_matter_the_snapshot() {
        var policy = new FixedConcurrencyPolicy(15);

        assertThat(policy.pressureLevel(new MemorySnapshot(0, 0, 0, 0, 0.99)))
                .isEqualTo(MemoryPressureLevel.LOW);
    }
}
