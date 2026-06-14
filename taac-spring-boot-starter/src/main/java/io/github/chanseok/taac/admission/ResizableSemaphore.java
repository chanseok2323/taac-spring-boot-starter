package io.github.chanseok.taac.admission;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 동적으로 capacity가 변하는 Semaphore.
 *
 * Java 표준 {@link Semaphore#reducePermits(int)}(protected)을 노출하고,
 * capacity 증가용 {@link #addPermits(int)} 와 진단용 메서드를 추가하여
 * 락/CAS 기반 외부 debt counter 없이 O(1) 동시성 조정을 가능하게 한다.
 *
 * ── 핵심 동작 ──
 *
 * Semaphore 내부 state는 음수가 될 수 있다. shrink 직후 in-flight 수가
 * 새 capacity보다 크면 available은 음수가 되며, 후속 release()가 한 단계씩
 * 양수까지 끌어올려 자연스럽게 debt가 흡수된다 (Java 표준 동작).
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
        int currentLogical = logicalCapacity.get();
        if (reduction > currentLogical) {
            throw new IllegalArgumentException(
                    "reduction " + reduction + " > logicalCapacity " + currentLogical);
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

    public int logicalCapacity() {
        return logicalCapacity.get();
    }

    public int borrowedPermits() {
        int avail = availablePermits();
        return avail < 0 ? -avail : 0;
    }
}
