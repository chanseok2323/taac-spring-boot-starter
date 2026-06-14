package io.github.chanseok.taac.policy;

/** Coarse classification of heap pressure derived from the configured thresholds. */
public enum MemoryPressureLevel {
    LOW, MODERATE, HIGH, CRITICAL
}
