package io.github.chanseok.taac.memory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;

/**
 * JMX-backed implementation with a 50 ms cache — keeps simultaneous acquires
 * from each hitting the MXBean.
 */
public final class JmxHeapMemoryMonitor implements HeapMemoryMonitor {

    private static final long CACHE_TTL_MS = 50;

    private final MemoryMXBean memoryMXBean = ManagementFactory.getMemoryMXBean();

    private volatile MemorySnapshot cached;
    private volatile long cachedAtMs;

    @Override
    public MemorySnapshot snapshot() {
        long now = System.currentTimeMillis();
        MemorySnapshot s = cached;
        if (s != null && (now - cachedAtMs) < CACHE_TTL_MS) {
            return s;
        }

        MemoryUsage heap = memoryMXBean.getHeapMemoryUsage();
        long used = heap.getUsed();
        long max = heap.getMax();
        s = new MemorySnapshot(used, heap.getCommitted(), max, now,
                max > 0 ? (double) used / max : 0.0);
        cached = s;
        cachedAtMs = now;
        return s;
    }
}
