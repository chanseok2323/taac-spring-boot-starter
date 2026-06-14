package io.github.chanseok.taac.memory;

/** Source of heap snapshots for the admission controller. */
public interface HeapMemoryMonitor {

    MemorySnapshot snapshot();

    default double currentUsageRatio() {
        return snapshot().usageRatio();
    }
}
