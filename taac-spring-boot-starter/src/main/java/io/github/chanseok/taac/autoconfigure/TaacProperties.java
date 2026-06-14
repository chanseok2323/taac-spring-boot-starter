package io.github.chanseok.taac.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

/**
 * Configuration bound from {@code taac.*}.
 *
 * <p>Defaults match the values the thesis evaluated against — start there
 * and tune {@code max-concurrency} / thresholds for your workload.
 */
@ConfigurationProperties(prefix = "taac")
public class TaacProperties {

    /** Turn the starter off without removing the dependency. */
    private boolean enabled = true;

    @NestedConfigurationProperty
    private final Admission admission = new Admission();

    @NestedConfigurationProperty
    private final Token token = new Token();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Admission getAdmission() { return admission; }
    public Token getToken() { return token; }

    public static class Admission {
        /** vegas | vegas-pure | response-time | standard-aimd | fixed | baseline. */
        private String policy = "vegas";

        private int maxConcurrency = 30;
        /** With vegas, must be ≥ {@link Token#getMaxWeight()} so the heaviest request can still fit. */
        private int minConcurrency = 5;

        /** Hard ceiling on how long {@code acquire} will wait before giving up. */
        private long timeoutMs = 30_000L;

        /** Background refresh tick. Smaller = faster reaction, more CPU. */
        private long evalIntervalMs = 50L;

        /** Pass-through to {@link java.util.concurrent.Semaphore} fairness. */
        private boolean fair = false;

        /** Reject immediately when the request can't possibly fit in the current target. */
        private boolean dynamicCapacityFailFast = true;

        /** {@code log-only} or {@code strict} — what to do if release weight underflows the counter. */
        private String underflowMode = "log-only";

        /** How long the DualQueue scheduler parks when idle. */
        private long schedulerIdleParkMs = 50L;

        /** Lock-free CAS shortcut for the no-contention path. */
        private boolean fastPathEnabled = true;

        @NestedConfigurationProperty
        private final Threshold threshold = new Threshold();

        public String getPolicy() { return policy; }
        public void setPolicy(String policy) { this.policy = policy; }
        public int getMaxConcurrency() { return maxConcurrency; }
        public void setMaxConcurrency(int v) { this.maxConcurrency = v; }
        public int getMinConcurrency() { return minConcurrency; }
        public void setMinConcurrency(int v) { this.minConcurrency = v; }
        public long getTimeoutMs() { return timeoutMs; }
        public void setTimeoutMs(long v) { this.timeoutMs = v; }
        public long getEvalIntervalMs() { return evalIntervalMs; }
        public void setEvalIntervalMs(long v) { this.evalIntervalMs = v; }
        public boolean isFair() { return fair; }
        public void setFair(boolean v) { this.fair = v; }
        public boolean isDynamicCapacityFailFast() { return dynamicCapacityFailFast; }
        public void setDynamicCapacityFailFast(boolean v) { this.dynamicCapacityFailFast = v; }
        public String getUnderflowMode() { return underflowMode; }
        public void setUnderflowMode(String v) { this.underflowMode = v; }
        public long getSchedulerIdleParkMs() { return schedulerIdleParkMs; }
        public void setSchedulerIdleParkMs(long v) { this.schedulerIdleParkMs = v; }
        public boolean isFastPathEnabled() { return fastPathEnabled; }
        public void setFastPathEnabled(boolean v) { this.fastPathEnabled = v; }
        public Threshold getThreshold() { return threshold; }
    }

    /** Heap-usage band boundaries used by the policies. */
    public static class Threshold {
        private double moderate = 0.70;
        private double high     = 0.85;
        private double critical = 0.92;

        public double getModerate() { return moderate; }
        public void setModerate(double v) { this.moderate = v; }
        public double getHigh() { return high; }
        public void setHigh(double v) { this.high = v; }
        public double getCritical() { return critical; }
        public void setCritical(double v) { this.critical = v; }
    }

    public static class Token {
        /** Starting point for the rolling average — gets corrected by actual traffic. */
        private int defaultAvg = 500;

        /** Per-request cap on how many permits a single call can consume. */
        private int maxWeight = 3;

        public int getDefaultAvg() { return defaultAvg; }
        public void setDefaultAvg(int v) { this.defaultAvg = v; }
        public int getMaxWeight() { return maxWeight; }
        public void setMaxWeight(int v) { this.maxWeight = v; }
    }
}
