package io.github.chanseok.taac.admission;

import io.github.chanseok.taac.policy.MemoryPressureLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CompositeAdmissionListenerTest {

    @Test
    void delegates_each_event_to_every_listener() {
        var hits = new AtomicInteger();
        AdmissionListener a = new AdmissionListener() {
            @Override public void onAcquired(AdmissionEvent.Acquired e) { hits.incrementAndGet(); }
        };
        AdmissionListener b = new AdmissionListener() {
            @Override public void onAcquired(AdmissionEvent.Acquired e) { hits.incrementAndGet(); }
        };

        new CompositeAdmissionListener(List.of(a, b))
                .onAcquired(new AdmissionEvent.Acquired(1, 0, 10, MemoryPressureLevel.LOW, 0.0));

        assertThat(hits).hasValue(2);
    }

    @Test
    void a_throwing_listener_does_not_stop_the_others() {
        var goodHits = new AtomicInteger();
        AdmissionListener bad = new AdmissionListener() {
            @Override public void onAcquireRequested(AdmissionEvent.AcquireRequested e) {
                throw new RuntimeException("boom");
            }
        };
        AdmissionListener good = new AdmissionListener() {
            @Override public void onAcquireRequested(AdmissionEvent.AcquireRequested e) {
                goodHits.incrementAndGet();
            }
        };

        var composite = new CompositeAdmissionListener(List.of(bad, good));

        assertThatCode(() -> composite.onAcquireRequested(new AdmissionEvent.AcquireRequested(1)))
                .doesNotThrowAnyException();
        assertThat(goodHits).hasValue(1);
    }

    @Test
    void null_list_is_treated_as_empty() {
        assertThatCode(() -> new CompositeAdmissionListener(null)
                .onReleased(new AdmissionEvent.Released(1)))
                .doesNotThrowAnyException();
    }
}
