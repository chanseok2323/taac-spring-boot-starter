package io.github.chanseok.taac.admission;

import io.github.chanseok.taac.memory.HeapMemoryMonitor;
import io.github.chanseok.taac.memory.MemorySnapshot;
import io.github.chanseok.taac.policy.ConcurrencyPolicy;
import io.github.chanseok.taac.policy.MemoryPressureLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PolicyDrivenAdmissionControllerTest {

    private SemaphoreAdmissionGate gate;
    private RecordingPolicy policy;
    private RecordingListener listener;
    private PolicyDrivenAdmissionController controller;

    @BeforeEach
    void setup() {
        gate = new SemaphoreAdmissionGate(5, false);
        policy = new RecordingPolicy();
        listener = new RecordingListener();
        HeapMemoryMonitor monitor = () -> new MemorySnapshot(0, 0, 0, 0, 0.10);
        controller = new PolicyDrivenAdmissionController(policy, gate, monitor,
                new CompositeAdmissionListener(List.of(listener)),
                5, 1_000, true);
    }

    @AfterEach
    void teardown() throws Exception {
        gate.close();
    }

    @Test
    void try_with_resources_releases_the_slot() {
        try (AdmissionToken token = controller.acquire(1)) {
            assertThat(gate.availablePermits()).isEqualTo(4);
            assertThat(token.result().permittedConcurrency()).isPositive();
        }

        assertThat(gate.availablePermits()).isEqualTo(5);
        assertThat(listener.released).hasValue(1);
    }

    @Test
    void close_is_idempotent() {
        AdmissionToken token = controller.acquire(1);
        token.close();
        token.close();

        assertThat(gate.availablePermits()).isEqualTo(5);
        assertThat(listener.released).hasValue(1);   // not two
    }

    @Test
    void recordCompletion_feeds_the_policy_and_fires_an_event() {
        try (AdmissionToken token = controller.acquire(1)) {
            token.recordCompletion(123, 50, 30);
        }

        assertThat(policy.lastResponseTimeMs).isEqualTo(123);
        assertThat(policy.lastInputTokens).isEqualTo(50);
        assertThat(policy.lastOutputTokens).isEqualTo(30);
        assertThat(listener.completed).hasValue(1);
    }

    @Test
    void over_capacity_request_is_rejected_with_a_failure_event() {
        assertThatThrownBy(() -> controller.acquire(10))
                .isInstanceOf(AdmissionTimeoutException.class);

        assertThat(listener.failed).hasValue(1);
        assertThat(listener.lastFailReason).isEqualTo(AdmissionEvent.FailureReason.OVER_CAPACITY);
    }

    @Test
    void heap_critical_in_evaluate_propagates_to_the_acquire_metadata() {
        policy.nextEvaluate = 3;   // controller will shrink the gate
        controller.refreshTarget();

        try (AdmissionToken token = controller.acquire(1)) {
            assertThat(token.result().permittedConcurrency()).isEqualTo(3);
        }
    }

    // --- test doubles ---------------------------------------------------------

    private static final class RecordingPolicy implements ConcurrencyPolicy {
        volatile int  nextEvaluate = 5;
        volatile long lastResponseTimeMs;
        volatile int  lastInputTokens;
        volatile int  lastOutputTokens;

        @Override public int evaluate(GateState state) { return nextEvaluate; }
        @Override public MemoryPressureLevel pressureLevel(MemorySnapshot snapshot) { return MemoryPressureLevel.LOW; }
        @Override public void recordCompletion(long rt, int in, int out) {
            lastResponseTimeMs = rt; lastInputTokens = in; lastOutputTokens = out;
        }
    }

    private static final class RecordingListener implements AdmissionListener {
        final AtomicInteger acquired = new AtomicInteger();
        final AtomicInteger released = new AtomicInteger();
        final AtomicInteger failed   = new AtomicInteger();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicReference<AdmissionEvent.FailureReason> lastFailReason = new AtomicReference<>();

        @Override public void onAcquired(AdmissionEvent.Acquired e)         { acquired.incrementAndGet(); }
        @Override public void onReleased(AdmissionEvent.Released e)         { released.incrementAndGet(); }
        @Override public void onAcquireFailed(AdmissionEvent.AcquireFailed e) {
            failed.incrementAndGet();
            lastFailReason.set(e.reason());
        }
        @Override public void onCompletion(AdmissionEvent.Completed e)       { completed.incrementAndGet(); }
    }
}
