package io.github.chanseok.taac.support;

import io.github.chanseok.taac.admission.AdmissionController;
import io.github.chanseok.taac.admission.AdmissionResult;
import io.github.chanseok.taac.admission.AdmissionToken;
import io.github.chanseok.taac.policy.MemoryPressureLevel;
import io.github.chanseok.taac.token.ApproximateTokenCounter;
import io.github.chanseok.taac.token.TokenCounter;
import io.github.chanseok.taac.token.TokenWeightTracker;
import io.github.chanseok.taac.token.WeightStrategy;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdmissionTemplateTest {

    private final TokenCounter   counter  = new ApproximateTokenCounter();
    private final WeightStrategy weights  = new TokenWeightTracker(500, 3);

    @Test
    void happy_path_closes_the_token_and_records_completion() {
        var ctl = new SpyController();
        var template = new AdmissionTemplate(ctl, counter, weights);

        String result = template.execute("hi there", String::toUpperCase);

        assertThat(result).isEqualTo("HI THERE");
        assertThat(ctl.lastToken.closed).isTrue();
        assertThat(ctl.lastToken.recordedTimes).hasValue(1);
    }

    @Test
    void token_is_closed_even_when_the_work_throws() {
        var ctl = new SpyController();
        var template = new AdmissionTemplate(ctl, counter, weights);

        assertThatThrownBy(() ->
                template.execute("hi", s -> { throw new RuntimeException("boom"); }))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");

        assertThat(ctl.lastToken.closed).isTrue();
    }

    @Test
    void executeWithTokens_skips_input_tokenisation() {
        var ctl = new SpyController();
        var template = new AdmissionTemplate(ctl, counter, weights);

        int result = template.executeWithTokens(800, 200, () -> 42);

        assertThat(result).isEqualTo(42);
        assertThat(ctl.lastWeight).isGreaterThanOrEqualTo(1);
        assertThat(ctl.lastToken.closed).isTrue();
    }

    // --- test doubles ---------------------------------------------------------

    private static final class SpyController implements AdmissionController {
        StubToken lastToken;
        int       lastWeight;

        @Override
        public AdmissionToken acquire(int weight) {
            lastWeight = weight;
            lastToken = new StubToken();
            return lastToken;
        }
    }

    private static final class StubToken implements AdmissionToken {
        private static final AdmissionResult INFO =
                new AdmissionResult(0L, 1, MemoryPressureLevel.LOW, 0.0);
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicInteger recordedTimes = new AtomicInteger();

        @Override public AdmissionResult result() { return INFO; }
        @Override public void recordCompletion(int in, int out) { recordedTimes.incrementAndGet(); }
        @Override public void recordCompletion(long rt, int in, int out) { recordedTimes.incrementAndGet(); }
        @Override public void close() { closed.set(true); }
    }
}
