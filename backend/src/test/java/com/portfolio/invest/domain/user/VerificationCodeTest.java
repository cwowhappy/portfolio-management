package com.portfolio.invest.domain.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class VerificationCodeTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    private VerificationCode code() {
        return VerificationCode.reconstitute(null, "a@x.com", VerificationPurpose.REGISTER,
                "$2a$hash", 0, null, NOW.plusSeconds(300), NOW);
    }

    @DisplayName("未过期未使用未耗尽为可用")
    @Test
    void whenFresh_thenNotUsedExpiredExhausted() {
        assertThat(code().isUsed()).isFalse();
        assertThat(code().isExpired(NOW.plusSeconds(299))).isFalse();
        assertThat(code().attemptsExhausted()).isFalse();
    }

    @DisplayName("过期判定以传入时刻为准")
    @Test
    void givenExpired_whenIsExpired_thenTrue() {
        assertThat(code().isExpired(NOW.plusSeconds(301))).isTrue();
    }

    @DisplayName("失败尝试累加且不改原实例")
    @Test
    void whenAttemptFailed_thenAttemptsIncrementedAndNewInstance() {
        VerificationCode c = code();
        VerificationCode failed = c.attemptFailed();
        assertThat(failed.attempts()).isEqualTo(1);
        assertThat(c.attempts()).isZero();
    }

    @DisplayName("五次失败后耗尽")
    @Test
    void givenFiveFailures_whenAttemptsExhausted_thenTrue() {
        VerificationCode c = code();
        for (int i = 0; i < 5; i++) c = c.attemptFailed();
        assertThat(c.attemptsExhausted()).isTrue();
    }

    @DisplayName("标记使用后即已用")
    @Test
    void whenMarkUsed_thenUsed() {
        assertThat(code().markUsed(NOW.plusSeconds(10)).isUsed()).isTrue();
    }
}
