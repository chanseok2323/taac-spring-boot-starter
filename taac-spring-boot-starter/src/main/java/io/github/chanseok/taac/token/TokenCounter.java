package io.github.chanseok.taac.token;

/**
 * Approximates token counts for a string. The library ships an
 * {@link ApproximateTokenCounter} (char-class heuristic, no deps); register
 * your own bean to plug in a real tokenizer like jtokkit or tiktoken.
 */
public interface TokenCounter {

    int count(String text);
}
