package io.github.chanseok.taac.metrics;

import io.github.chanseok.taac.admission.AdmissionEvent;
import io.github.chanseok.taac.admission.AdmissionListener;

/** Bridges the listener bus into {@link AdmissionMetrics}. */
public final class MetricsAdmissionListener implements AdmissionListener {

    private final AdmissionMetrics metrics;

    public MetricsAdmissionListener(AdmissionMetrics metrics) {
        this.metrics = metrics;
    }

    @Override public void onAcquireRequested(AdmissionEvent.AcquireRequested e) { metrics.recordWaitStart(); }
    @Override public void onAcquired(AdmissionEvent.Acquired e)                 { metrics.recordWaitEnd(e.waitTimeMs(), e.pressureLevel()); }
    @Override public void onAcquireFailed(AdmissionEvent.AcquireFailed e)       { metrics.recordTimeout(); }
    @Override public void onReleased(AdmissionEvent.Released e)                 { metrics.recordRelease(); }
}
