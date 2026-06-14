package io.github.chanseok.taac.admission;

import java.util.concurrent.TimeUnit;

/**
 * {@link java.util.concurrent.Semaphore}-backed gate. The weight is passed
 * straight through as the permit count; works well for the policies that
 * treat every request as one unit (response-time, AIMD, pure-Vegas, fixed).
 */
public final class SemaphoreAdmissionGate implements AdmissionGate {

    private final ResizableSemaphore semaphore;

    public SemaphoreAdmissionGate(int initialCapacity, boolean fair) {
        this.semaphore = new ResizableSemaphore(initialCapacity, fair);
    }

    @Override
    public boolean acquire(int weight, long timeoutMs) throws InterruptedException {
        return semaphore.tryAcquire(Math.max(1, weight), timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void release(int weight) {
        semaphore.release(Math.max(1, weight));
    }

    @Override
    public void resize(int delta) {
        if (delta > 0) semaphore.addPermits(delta);
        else if (delta < 0) semaphore.reducePermits(-delta);
    }

    @Override public int availablePermits() { return semaphore.availablePermits(); }
    @Override public int logicalCapacity()  { return semaphore.logicalCapacity(); }
    @Override public int getQueueLength()   { return semaphore.getQueueLength(); }
    @Override public int borrowedPermits()  { return semaphore.borrowedPermits(); }
}
