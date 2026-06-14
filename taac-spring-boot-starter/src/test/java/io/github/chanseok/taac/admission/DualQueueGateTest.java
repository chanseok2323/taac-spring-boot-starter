package io.github.chanseok.taac.admission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DualQueueGateTest {

    private DualQueueGate gate;

    @AfterEach
    void cleanup() {
        if (gate != null) gate.shutdown();
    }

    @Test
    void uncontended_acquire_uses_the_fast_path() throws Exception {
        gate = new DualQueueGate(3, false);

        assertThat(gate.acquire(1, 1_000)).isTrue();
        assertThat(gate.acquire(1, 1_000)).isTrue();

        assertThat(gate.availablePermits()).isEqualTo(1);
    }

    @Test
    void releasing_a_permit_lets_a_waiter_through() throws Exception {
        gate = new DualQueueGate(1, false);
        assertThat(gate.acquire(1, 100)).isTrue();   // consume capacity

        var acquired = new AtomicInteger();
        var waiter = new Thread(() -> {
            try {
                if (gate.acquire(1, 5_000)) acquired.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        // Waiter is parked; queue has the one outstanding caller.
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(gate.getQueueLength()).isEqualTo(1));

        gate.release(1);

        waiter.join(2_000);
        assertThat(acquired).hasValue(1);
        assertThat(gate.getQueueLength()).isZero();
    }

    @Test
    void timeout_returns_false_and_clears_the_waiter() throws Exception {
        gate = new DualQueueGate(1, false);
        gate.acquire(1, 100);  // hold capacity

        boolean got = gate.acquire(1, 50);   // can't get it, give up after 50ms

        assertThat(got).isFalse();
        await().atMost(500, TimeUnit.MILLISECONDS).untilAsserted(() ->
                assertThat(gate.getQueueLength()).isZero());
    }

    @Test
    void grow_via_addPermits_wakes_a_waiter() throws Exception {
        gate = new DualQueueGate(1, false);
        gate.acquire(1, 100);

        var got = new AtomicInteger();
        var t = new Thread(() -> {
            try {
                if (gate.acquire(1, 5_000)) got.incrementAndGet();
            } catch (InterruptedException ignored) { }
        });
        t.start();

        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(gate.getQueueLength()).isEqualTo(1));

        gate.addPermits(1);

        t.join(2_000);
        assertThat(got).hasValue(1);
    }

    @Test
    void smaller_weight_overtakes_a_blocking_heavy_request_within_the_scan_window() throws Exception {
        gate = new DualQueueGate(2, false);
        // Saturate the gate.
        gate.acquire(1, 100);
        gate.acquire(1, 100);

        // Heavy request enqueues first and can't fit (weight 2 vs 0 free).
        var heavyGranted = new AtomicInteger();
        var heavy = new Thread(() -> {
            try {
                if (gate.acquire(2, 5_000)) heavyGranted.incrementAndGet();
            } catch (InterruptedException ignored) { }
        });
        heavy.start();
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(gate.getQueueLength()).isEqualTo(1));

        // Light request enqueues second.
        var lightGranted = new AtomicInteger();
        var light = new Thread(() -> {
            try {
                if (gate.acquire(1, 5_000)) lightGranted.incrementAndGet();
            } catch (InterruptedException ignored) { }
        });
        light.start();
        await().atMost(1, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(gate.getQueueLength()).isEqualTo(2));

        // Free one slot — only the light request fits, scheduler should pick it.
        gate.release(1);

        light.join(2_000);
        assertThat(lightGranted).hasValue(1);
        assertThat(heavyGranted).hasValue(0);

        // Free the rest; heavy should now make it.
        gate.release(1);
        gate.release(1);
        heavy.join(2_000);
        assertThat(heavyGranted).hasValue(1);
    }

    @Test
    void survives_high_concurrent_acquire_release() throws Exception {
        gate = new DualQueueGate(8, false);
        int workers = 32;
        int perWorker = 200;

        var pool = Executors.newFixedThreadPool(workers);
        var done = new CountDownLatch(workers);
        var failures = new AtomicInteger();

        for (int w = 0; w < workers; w++) {
            pool.submit(() -> {
                try {
                    for (int i = 0; i < perWorker; i++) {
                        if (gate.acquire(1, 5_000)) {
                            try {
                                Thread.sleep(1);
                            } finally {
                                gate.release(1);
                            }
                        } else {
                            failures.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(failures).hasValue(0);
        assertThat(gate.availablePermits()).isEqualTo(8);
        assertThat(gate.getQueueLength()).isZero();
    }
}
