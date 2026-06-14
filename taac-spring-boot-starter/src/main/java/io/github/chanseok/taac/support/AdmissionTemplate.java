package io.github.chanseok.taac.support;

import io.github.chanseok.taac.admission.AdmissionController;
import io.github.chanseok.taac.admission.AdmissionToken;
import io.github.chanseok.taac.token.TokenCounter;
import io.github.chanseok.taac.token.WeightStrategy;

import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;

/**
 * Convenience facade — counts tokens, picks the weight, runs the work
 * under an admission slot, feeds the response time back into the policy.
 *
 * <p>Modelled after {@code JdbcTemplate} / {@code TransactionTemplate} —
 * a single {@code execute(...)} call replaces the manual try / acquire /
 * recordCompletion / close dance.
 */
public class AdmissionTemplate {

    private final AdmissionController controller;
    private final TokenCounter tokenCounter;
    private final WeightStrategy weightStrategy;

    public AdmissionTemplate(AdmissionController controller,
                             TokenCounter tokenCounter,
                             WeightStrategy weightStrategy) {
        this.controller = controller;
        this.tokenCounter = tokenCounter;
        this.weightStrategy = weightStrategy;
    }

    /** Common case — string in, string out. Token counts are derived automatically. */
    public String execute(String input, Function<String, String> work) {
        return execute(input, work, tokenCounter::count);
    }

    /** When the output isn't a string, supply your own output token counter. */
    public <T> T execute(String input,
                         Function<String, T> work,
                         ToIntFunction<? super T> outputTokenizer) {
        int inputTokens = tokenCounter.count(input);
        int weight = weightStrategy.weightFor(inputTokens);
        try (AdmissionToken token = controller.acquire(weight)) {
            T result = work.apply(input);
            int outputTokens = result == null ? 0 : Math.max(0, outputTokenizer.applyAsInt(result));
            token.recordCompletion(inputTokens, outputTokens);
            weightStrategy.recordInput(inputTokens);
            return result;
        }
    }

    /** Token counts supplied by the caller — for non-string workloads. */
    public <T> T executeWithTokens(int inputTokens, int outputTokens, Supplier<T> work) {
        int weight = weightStrategy.weightFor(Math.max(0, inputTokens));
        try (AdmissionToken token = controller.acquire(weight)) {
            T result = work.get();
            token.recordCompletion(inputTokens, Math.max(0, outputTokens));
            weightStrategy.recordInput(inputTokens);
            return result;
        }
    }

    /** Escape hatch for callers that need to talk to the controller directly. */
    public AdmissionController controller() { return controller; }
}
