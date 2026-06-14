package io.github.chanseok.taac.autoconfigure;

import io.github.chanseok.taac.admission.AdmissionController;
import io.github.chanseok.taac.admission.AdmissionGate;
import io.github.chanseok.taac.admission.AdmissionListener;
import io.github.chanseok.taac.admission.CompositeAdmissionListener;
import io.github.chanseok.taac.admission.DualQueueAdmissionGate;
import io.github.chanseok.taac.admission.NoOpAdmissionController;
import io.github.chanseok.taac.admission.PolicyDrivenAdmissionController;
import io.github.chanseok.taac.admission.SemaphoreAdmissionGate;
import io.github.chanseok.taac.memory.HeapMemoryMonitor;
import io.github.chanseok.taac.memory.JmxHeapMemoryMonitor;
import io.github.chanseok.taac.metrics.AdmissionMetrics;
import io.github.chanseok.taac.metrics.MetricsAdmissionListener;
import io.github.chanseok.taac.policy.ConcurrencyPolicy;
import io.github.chanseok.taac.policy.FixedConcurrencyPolicy;
import io.github.chanseok.taac.policy.PureVegasPolicy;
import io.github.chanseok.taac.policy.ResponseTimeBasedConcurrencyPolicy;
import io.github.chanseok.taac.policy.ResponseTimeTracker;
import io.github.chanseok.taac.policy.StandardAimdPolicy;
import io.github.chanseok.taac.policy.TokenAwareVegasPolicy;
import io.github.chanseok.taac.support.AdmissionTemplate;
import io.github.chanseok.taac.token.ApproximateTokenCounter;
import io.github.chanseok.taac.token.TokenCounter;
import io.github.chanseok.taac.token.TokenWeightTracker;
import io.github.chanseok.taac.token.WeightStrategy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * TAAC autoconfiguration.
 *
 * <p>Every bean here is {@code @ConditionalOnMissingBean} — declare your own
 * {@code @Bean} of the same type to replace it. The active policy and gate
 * are selected by {@code taac.admission.policy}; everything else falls into
 * place around that choice.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "taac", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(TaacProperties.class)
@EnableScheduling
public class TaacAutoConfiguration {

    private static final String POLICY = "taac.admission.policy";

    // --- shared infrastructure ------------------------------------------------

    @Bean @ConditionalOnMissingBean
    public TokenCounter taacTokenCounter() {
        return new ApproximateTokenCounter();
    }

    @Bean @ConditionalOnMissingBean
    public HeapMemoryMonitor taacHeapMemoryMonitor() {
        return new JmxHeapMemoryMonitor();
    }

    @Bean @ConditionalOnMissingBean
    public AdmissionMetrics taacAdmissionMetrics() {
        return new AdmissionMetrics();
    }

    @Bean @ConditionalOnMissingBean
    public WeightStrategy taacWeightStrategy(TaacProperties props) {
        return new TokenWeightTracker(props.getToken().getDefaultAvg(), props.getToken().getMaxWeight());
    }

    /** Default observer — metrics. Shares the bus with any user listeners. */
    @Bean
    public AdmissionListener taacMetricsAdmissionListener(AdmissionMetrics metrics) {
        return new MetricsAdmissionListener(metrics);
    }

    // --- policies (exactly one is active) -------------------------------------

    @Bean @ConditionalOnMissingBean
    @ConditionalOnProperty(name = POLICY, havingValue = "vegas", matchIfMissing = true)
    public ConcurrencyPolicy taacTokenAwareVegasPolicy(TaacProperties props) {
        var a = props.getAdmission();
        int maxWeight = props.getToken().getMaxWeight();
        // Heaviest request needs a permit budget at least its own size, or
        // it sits at the head of the queue forever.
        if (a.getMinConcurrency() < maxWeight) {
            throw new IllegalStateException(
                    "taac.admission.min-concurrency (" + a.getMinConcurrency() + ")"
                            + " must be >= taac.token.max-weight (" + maxWeight
                            + ") for the vegas policy");
        }
        return new TokenAwareVegasPolicy(a.getMaxConcurrency(), a.getMinConcurrency(),
                a.getThreshold().getModerate(), a.getThreshold().getHigh(), a.getThreshold().getCritical());
    }

    @Bean @ConditionalOnMissingBean
    @ConditionalOnProperty(name = POLICY, havingValue = "vegas-pure")
    public ConcurrencyPolicy taacPureVegasPolicy(TaacProperties props) {
        var a = props.getAdmission();
        return new PureVegasPolicy(a.getMaxConcurrency(), a.getMinConcurrency(),
                a.getThreshold().getModerate(), a.getThreshold().getHigh(), a.getThreshold().getCritical());
    }

    @Bean @ConditionalOnMissingBean
    @ConditionalOnProperty(name = POLICY, havingValue = "response-time")
    public ResponseTimeTracker taacResponseTimeTracker() {
        return new ResponseTimeTracker();
    }

    @Bean @ConditionalOnMissingBean
    @ConditionalOnProperty(name = POLICY, havingValue = "response-time")
    public ConcurrencyPolicy taacResponseTimePolicy(ResponseTimeTracker tracker, TaacProperties props) {
        var a = props.getAdmission();
        return new ResponseTimeBasedConcurrencyPolicy(tracker,
                a.getMaxConcurrency(), a.getMinConcurrency(),
                a.getThreshold().getModerate(), a.getThreshold().getHigh(), a.getThreshold().getCritical());
    }

    @Bean @ConditionalOnMissingBean
    @ConditionalOnProperty(name = POLICY, havingValue = "standard-aimd")
    public ConcurrencyPolicy taacStandardAimdPolicy(TaacProperties props) {
        var a = props.getAdmission();
        return new StandardAimdPolicy(a.getMaxConcurrency(), a.getMinConcurrency(),
                a.getThreshold().getModerate(), a.getThreshold().getHigh(), a.getThreshold().getCritical());
    }

    @Bean @ConditionalOnMissingBean
    @ConditionalOnProperty(name = POLICY, havingValue = "fixed")
    public ConcurrencyPolicy taacFixedPolicy(TaacProperties props) {
        return new FixedConcurrencyPolicy(props.getAdmission().getMaxConcurrency());
    }

    // --- gates ----------------------------------------------------------------
    //
    // Vegas registers its specialised gate; everyone else falls through to the
    // Semaphore-backed default, which kicks in when a ConcurrencyPolicy exists
    // but no AdmissionGate has been registered yet.

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = POLICY, havingValue = "vegas", matchIfMissing = true)
    public AdmissionGate taacDualQueueGate(TaacProperties props) {
        var a = props.getAdmission();
        return new DualQueueAdmissionGate(a.getMaxConcurrency(), a.isFair(),
                a.getUnderflowMode(), a.getSchedulerIdleParkMs(), a.isFastPathEnabled());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnBean(ConcurrencyPolicy.class)
    public AdmissionGate taacSemaphoreGate(TaacProperties props) {
        var a = props.getAdmission();
        return new SemaphoreAdmissionGate(a.getMaxConcurrency(), a.isFair());
    }

    // --- controllers ----------------------------------------------------------

    @Bean
    @ConditionalOnMissingBean(AdmissionController.class)
    @ConditionalOnProperty(name = POLICY, havingValue = "baseline")
    public AdmissionController taacNoOpAdmissionController() {
        return new NoOpAdmissionController();
    }

    @Bean
    @ConditionalOnMissingBean(AdmissionController.class)
    @ConditionalOnBean({ConcurrencyPolicy.class, AdmissionGate.class})
    public AdmissionController taacAdmissionController(ConcurrencyPolicy policy,
                                                       AdmissionGate gate,
                                                       HeapMemoryMonitor memoryMonitor,
                                                       ObjectProvider<AdmissionListener> listeners,
                                                       TaacProperties props) {
        var a = props.getAdmission();
        AdmissionListener composite = new CompositeAdmissionListener(listeners.orderedStream().toList());
        return new PolicyDrivenAdmissionController(policy, gate, memoryMonitor, composite,
                a.getMaxConcurrency(), a.getTimeoutMs(), a.isDynamicCapacityFailFast());
    }

    // --- user-facing facade ---------------------------------------------------

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AdmissionController.class)
    public AdmissionTemplate taacAdmissionTemplate(AdmissionController controller,
                                                   TokenCounter tokenCounter,
                                                   WeightStrategy weightStrategy) {
        return new AdmissionTemplate(controller, tokenCounter, weightStrategy);
    }
}
