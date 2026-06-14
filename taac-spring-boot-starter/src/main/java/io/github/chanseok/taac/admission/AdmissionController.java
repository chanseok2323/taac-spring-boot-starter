package io.github.chanseok.taac.admission;

/**
 * Hand a request to the admission gate before doing the heavy work.
 *
 * <p>Prefer {@link io.github.chanseok.taac.support.AdmissionTemplate} for
 * day-to-day use — it wraps acquire / completion / release in one call
 * and handles token counting. Use this interface directly only when the
 * template's shape doesn't fit.
 */
public interface AdmissionController {

    /** Acquire one slot. */
    default AdmissionToken acquire() {
        return acquire(1);
    }

    /**
     * Acquire {@code weight} slots — for token-aware policies this should be
     * proportional to the cost of the request.
     */
    AdmissionToken acquire(int weight);
}
