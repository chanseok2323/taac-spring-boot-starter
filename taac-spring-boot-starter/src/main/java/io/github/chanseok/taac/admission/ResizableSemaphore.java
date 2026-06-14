package io.github.chanseok.taac.admission;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Semaphore whose capacity can be grown and shrunk at runtime.
 *
 * <p>{@link Semaphore#reducePermits(int)} is protected on the standard class,
 * so we expose it and add {@link #addPermits(int)} for the opposite direction.
 * Shrinking can temporarily push {@code availablePermits()} below zero — that
 * "debt" is paid down by subsequent {@code release} calls, exactly the way
 * the standard JDK class is documented to behave.
 */
public class ResizableSemaphore extends Semaphore {

    private final AtomicInteger logicalCapacity;

    public ResizableSemaphore(int permits, boolean fair) {
        super(permits, fair);
        if (permits < 0) {
            throw new IllegalArgumentException("permits < 0: " + permits);
        }
        this.logicalCapacity = new AtomicInteger(permits);
    }

    @Override
    public void reducePermits(int reduction) {
        if (reduction < 0) {
            throw new IllegalArgumentException("reduction < 0: " + reduction);
        }
        if (reduction == 0) return;
        int current = logicalCapacity.get();
        if (reduction > current) {
            throw new IllegalArgumentException(
                    "reduction " + reduction + " > logicalCapacity " + current);
        }
        super.reducePermits(reduction);
        logicalCapacity.addAndGet(-reduction);
    }

    public void addPermits(int addition) {
        if (addition < 0) {
            throw new IllegalArgumentException("addition < 0: " + addition);
        }
        if (addition == 0) return;
        super.release(addition);
        logicalCapacity.addAndGet(addition);
    }

    /** Target capacity tracked separately from the (possibly negative) available count. */
    public int logicalCapacity() {
        return logicalCapacity.get();
    }

    /** Magnitude of negative {@code availablePermits} — in-flight calls beyond capacity. */
    public int borrowedPermits() {
        int avail = availablePermits();
        return avail < 0 ? -avail : 0;
    }
}
