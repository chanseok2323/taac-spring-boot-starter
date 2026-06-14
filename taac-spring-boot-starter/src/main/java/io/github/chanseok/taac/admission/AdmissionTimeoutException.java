package io.github.chanseok.taac.admission;

/** Thrown when {@link AdmissionController#acquire(int)} can't grant a slot. */
public class AdmissionTimeoutException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AdmissionTimeoutException(String message) {
        super(message);
    }
}
