package io.github.chanseok.taac.admission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Fans events out to a list of listeners. A throwing listener is logged and skipped
 * so one bad observer can't take the controller down.
 */
public final class CompositeAdmissionListener implements AdmissionListener {

    private static final Logger log = LoggerFactory.getLogger(CompositeAdmissionListener.class);

    private final List<AdmissionListener> delegates;

    public CompositeAdmissionListener(List<? extends AdmissionListener> delegates) {
        this.delegates = delegates == null ? List.of() : List.copyOf(delegates);
    }

    @Override public void onAcquireRequested(AdmissionEvent.AcquireRequested e) { dispatch(l -> l.onAcquireRequested(e)); }
    @Override public void onAcquired(AdmissionEvent.Acquired e)                 { dispatch(l -> l.onAcquired(e)); }
    @Override public void onAcquireFailed(AdmissionEvent.AcquireFailed e)       { dispatch(l -> l.onAcquireFailed(e)); }
    @Override public void onReleased(AdmissionEvent.Released e)                 { dispatch(l -> l.onReleased(e)); }
    @Override public void onCompletion(AdmissionEvent.Completed e)              { dispatch(l -> l.onCompletion(e)); }

    private void dispatch(Consumer<AdmissionListener> sink) {
        for (AdmissionListener l : delegates) {
            try {
                sink.accept(l);
            } catch (Throwable t) {
                log.warn("admission listener threw, ignoring: {}", t.toString());
            }
        }
    }
}
