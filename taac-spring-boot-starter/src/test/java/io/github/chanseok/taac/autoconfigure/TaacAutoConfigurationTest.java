package io.github.chanseok.taac.autoconfigure;

import io.github.chanseok.taac.admission.AdmissionController;
import io.github.chanseok.taac.admission.AdmissionGate;
import io.github.chanseok.taac.admission.AdmissionListener;
import io.github.chanseok.taac.admission.DualQueueAdmissionGate;
import io.github.chanseok.taac.admission.NoOpAdmissionController;
import io.github.chanseok.taac.admission.PolicyDrivenAdmissionController;
import io.github.chanseok.taac.admission.SemaphoreAdmissionGate;
import io.github.chanseok.taac.policy.ConcurrencyPolicy;
import io.github.chanseok.taac.policy.FixedConcurrencyPolicy;
import io.github.chanseok.taac.policy.PureVegasPolicy;
import io.github.chanseok.taac.policy.ResponseTimeBasedConcurrencyPolicy;
import io.github.chanseok.taac.policy.StandardAimdPolicy;
import io.github.chanseok.taac.policy.TokenAwareVegasPolicy;
import io.github.chanseok.taac.support.AdmissionTemplate;
import io.github.chanseok.taac.token.TokenCounter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class TaacAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaacAutoConfiguration.class));

    @Test
    void defaults_produce_a_vegas_setup() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ConcurrencyPolicy.class)
                    .getBean(ConcurrencyPolicy.class)
                    .isInstanceOf(TokenAwareVegasPolicy.class);

            assertThat(ctx).hasSingleBean(AdmissionGate.class)
                    .getBean(AdmissionGate.class)
                    .isInstanceOf(DualQueueAdmissionGate.class);

            assertThat(ctx).hasSingleBean(AdmissionController.class)
                    .getBean(AdmissionController.class)
                    .isInstanceOf(PolicyDrivenAdmissionController.class);

            assertThat(ctx).hasSingleBean(AdmissionTemplate.class);
        });
    }

    @Test
    void vegas_pure_uses_a_semaphore_gate() {
        runner.withPropertyValues("taac.admission.policy=vegas-pure").run(ctx -> {
            assertThat(ctx).getBean(ConcurrencyPolicy.class).isInstanceOf(PureVegasPolicy.class);
            assertThat(ctx).getBean(AdmissionGate.class).isInstanceOf(SemaphoreAdmissionGate.class);
        });
    }

    @Test
    void response_time_wires_the_tracker() {
        runner.withPropertyValues("taac.admission.policy=response-time").run(ctx -> {
            assertThat(ctx).getBean(ConcurrencyPolicy.class)
                    .isInstanceOf(ResponseTimeBasedConcurrencyPolicy.class);
            assertThat(ctx).hasSingleBean(io.github.chanseok.taac.policy.ResponseTimeTracker.class);
            assertThat(ctx).getBean(AdmissionGate.class).isInstanceOf(SemaphoreAdmissionGate.class);
        });
    }

    @Test
    void standard_aimd_picks_the_aimd_policy() {
        runner.withPropertyValues("taac.admission.policy=standard-aimd").run(ctx ->
                assertThat(ctx).getBean(ConcurrencyPolicy.class).isInstanceOf(StandardAimdPolicy.class));
    }

    @Test
    void fixed_picks_the_fixed_policy() {
        runner.withPropertyValues("taac.admission.policy=fixed").run(ctx ->
                assertThat(ctx).getBean(ConcurrencyPolicy.class).isInstanceOf(FixedConcurrencyPolicy.class));
    }

    @Test
    void baseline_registers_the_noop_controller_and_no_policy_or_gate() {
        runner.withPropertyValues("taac.admission.policy=baseline").run(ctx -> {
            assertThat(ctx).getBean(AdmissionController.class).isInstanceOf(NoOpAdmissionController.class);
            assertThat(ctx).doesNotHaveBean(ConcurrencyPolicy.class);
            assertThat(ctx).doesNotHaveBean(AdmissionGate.class);
            assertThat(ctx).doesNotHaveBean(AdmissionTemplate.class);
        });
    }

    @Test
    void taac_enabled_false_disables_everything() {
        runner.withPropertyValues("taac.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(AdmissionController.class);
            assertThat(ctx).doesNotHaveBean(AdmissionTemplate.class);
            assertThat(ctx).doesNotHaveBean(ConcurrencyPolicy.class);
        });
    }

    @Test
    void user_token_counter_replaces_the_default() {
        runner.withUserConfiguration(CustomCounterConfig.class).run(ctx ->
                assertThat(ctx).getBean(TokenCounter.class).isSameAs(CustomCounterConfig.INSTANCE));
    }

    @Test
    void user_listener_is_collected_alongside_the_metrics_listener() {
        runner.withUserConfiguration(CustomListenerConfig.class).run(ctx ->
                assertThat(ctx.getBeansOfType(AdmissionListener.class)).hasSizeGreaterThanOrEqualTo(2));
    }

    @Test
    void vegas_rejects_min_concurrency_smaller_than_max_weight() {
        runner.withPropertyValues(
                "taac.admission.policy=vegas",
                "taac.admission.min-concurrency=2",
                "taac.token.max-weight=3"
        ).run(ctx -> assertThat(ctx).hasFailed());
    }

    // --- helpers --------------------------------------------------------------

    @Configuration
    static class CustomCounterConfig {
        static final TokenCounter INSTANCE = text -> 42;
        @Bean TokenCounter customTokenCounter() { return INSTANCE; }
    }

    @Configuration
    static class CustomListenerConfig {
        @Bean AdmissionListener customListener() { return new AdmissionListener() {}; }
    }
}
