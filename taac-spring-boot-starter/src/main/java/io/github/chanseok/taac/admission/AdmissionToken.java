package io.github.chanseok.taac.admission;

/**
 * Handle returned by {@link AdmissionController#acquire(int)}.
 *
 * <p>Use with try-with-resources — {@link #close()} releases the slot and is
 * idempotent. Call {@link #recordCompletion(int, int)} before {@code close}
 * if the policy needs response-time / token signals (it usually does).
 *
 * <pre>{@code
 * try (var token = admission.acquire(weight)) {
 *     var resp = llm.call(prompt);
 *     token.recordCompletion(inputTokens, tokenCounter.count(resp));
 * }
 * }</pre>
 */
public interface AdmissionToken extends AutoCloseable {

    /** Metadata captured at acquire time. */
    AdmissionResult result();

    /**
     * Record completion using the token's own elapsed time
     * (from acquire to now). Most callers want this overload.
     */
    void recordCompletion(int inputTokens, int outputTokens);

    /** Record completion with an explicitly-measured response time. */
    void recordCompletion(long responseTimeMs, int inputTokens, int outputTokens);

    /** Release the slot. Safe to call more than once. */
    @Override
    void close();
}
