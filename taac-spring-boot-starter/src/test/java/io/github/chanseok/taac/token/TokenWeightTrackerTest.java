package io.github.chanseok.taac.token;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenWeightTrackerTest {

    @Test
    void weight_clamps_to_one_when_request_is_at_or_below_average() {
        var tracker = new TokenWeightTracker(500, 3);

        assertThat(tracker.weightFor(100)).isOne();
        assertThat(tracker.weightFor(500)).isOne();
    }

    @Test
    void weight_grows_proportionally_up_to_the_cap() {
        var tracker = new TokenWeightTracker(500, 3);

        assertThat(tracker.weightFor(1000)).isEqualTo(2);
        assertThat(tracker.weightFor(1500)).isEqualTo(3);
        // Anything bigger still caps at maxWeight.
        assertThat(tracker.weightFor(50_000)).isEqualTo(3);
    }

    @Test
    void zero_or_negative_input_still_costs_one_permit() {
        var tracker = new TokenWeightTracker(500, 3);

        assertThat(tracker.weightFor(0)).isOne();
        assertThat(tracker.weightFor(-100)).isOne();
    }

    @Test
    void recordInput_drags_the_rolling_average_towards_the_observed_value() {
        var tracker = new TokenWeightTracker(500, 3);

        for (int i = 0; i < 200; i++) {
            tracker.recordInput(2000);
        }

        // EMA should have pulled the average well above the original default.
        assertThat(tracker.currentAvgTokens()).isGreaterThan(1500);
    }

    @Test
    void max_weight_is_exposed_to_callers() {
        assertThat(new TokenWeightTracker(500, 5).maxWeight()).isEqualTo(5);
    }
}
