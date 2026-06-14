package io.github.chanseok.taac.token;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ApproximateTokenCounterTest {

    private final TokenCounter counter = new ApproximateTokenCounter();

    @Test
    void empty_and_null_count_zero() {
        assertThat(counter.count(null)).isZero();
        assertThat(counter.count("")).isZero();
    }

    @Test
    void single_char_always_rounds_up_to_one_token() {
        assertThat(counter.count("a")).isOne();
        assertThat(counter.count("가")).isOne();
    }

    @Test
    void ascii_uses_about_four_chars_per_token() {
        // 16 ASCII chars / 4 → 4 tokens.
        assertThat(counter.count("hello world test")).isEqualTo(4);
    }

    @Test
    void cjk_uses_about_two_chars_per_token() {
        // 6 CJK chars / 2 → 3 tokens.
        assertThat(counter.count("동시성제어법")).isEqualTo(3);
    }

    @Test
    void mixed_input_combines_both_rates() {
        // 4 ASCII (=1) + 4 CJK (=2). ceil(1+2) = 3.
        assertThat(counter.count("test테스트한글")).isGreaterThanOrEqualTo(3);
    }
}
