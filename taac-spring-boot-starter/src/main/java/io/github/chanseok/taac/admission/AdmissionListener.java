package io.github.chanseok.taac.admission;

/**
 * Observer for admission gate events. Override only the methods you care
 * about — register the listener as a Spring bean and the autoconfiguration
 * picks it up. Multiple listeners are supported; honour {@code @Order} if
 * you need a specific dispatch order.
 *
 * <p>Implementations should be cheap and must not throw — the controller
 * swallows exceptions but a slow listener still blocks the hot path.
 */
public interface AdmissionListener {

    default void onAcquireRequested(AdmissionEvent.AcquireRequested event) {}
    default void onAcquired(AdmissionEvent.Acquired event) {}
    default void onAcquireFailed(AdmissionEvent.AcquireFailed event) {}
    default void onReleased(AdmissionEvent.Released event) {}
    default void onCompletion(AdmissionEvent.Completed event) {}
}
