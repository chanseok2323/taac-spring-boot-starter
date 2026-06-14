package io.github.chanseok.taac.memory;

/** Immutable heap reading. {@code usageRatio} is pre-computed to avoid a divide on every check. */
public record MemorySnapshot(
        long usedBytes,
        long committedBytes,
        long maxBytes,
        long timestamp,
        double usageRatio
) {

    /** Safe initial value when no reading has happened yet — looks idle. */
    public static final MemorySnapshot EMPTY = new MemorySnapshot(0L, 0L, 0L, 0L, 0.0);

    public double usagePercent() { return usageRatio * 100.0; }
    public long   freeBytes()    { return maxBytes - usedBytes; }
    public double usedMB()       { return usedBytes / (1024.0 * 1024.0); }
    public double freeMB()       { return freeBytes() / (1024.0 * 1024.0); }
}
