package io.github.chanseok.taac.token;

/**
 * Cheap char-class heuristic — ~4 ASCII chars/token, ~2 CJK chars/token.
 * Accuracy doesn't matter much: admission only needs requests to be ranked
 * by relative size, not absolute count.
 */
public final class ApproximateTokenCounter implements TokenCounter {

    private static final double ASCII_CHARS_PER_TOKEN = 4.0;
    private static final double NON_ASCII_CHARS_PER_TOKEN = 2.0;

    @Override
    public int count(String text) {
        if (text == null || text.isEmpty()) return 0;

        int ascii = 0, nonAscii = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 128) ascii++; else nonAscii++;
        }
        double tokens = ascii / ASCII_CHARS_PER_TOKEN + nonAscii / NON_ASCII_CHARS_PER_TOKEN;
        return Math.max(1, (int) Math.ceil(tokens));
    }
}
