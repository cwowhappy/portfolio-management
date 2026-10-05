package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.portfolio.invest.domain.intelligence.BindingCodeRepository;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import com.portfolio.invest.domain.user.VerificationCodeRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 情报数据滚动清理切片：新闻 90 天/绑定码 1 天/验证码 90 天三个 cutoff 口径（clock 注入
 * 算死）、任一步失败不挡其余两步、三步全炸时调度入口顶层吞异常不炸调度线程。
 */
class IntelligenceCleanupServiceTest {

    /** 固定时刻（上海 2026-09-29 04:07 → UTC 前一晚 20:07）。 */
    private static final Instant NOW = Instant.parse("2026-09-28T20:07:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));

    private final NewsRepository newsRepository = mock(NewsRepository.class);
    private final BindingCodeRepository bindingCodeRepository = mock(BindingCodeRepository.class);
    private final VerificationCodeRepository codeRepository = mock(VerificationCodeRepository.class);
    private IntelligenceCleanupService service;

    @BeforeEach
    void setUp() {
        service = new IntelligenceCleanupService(newsRepository, bindingCodeRepository,
                codeRepository, CLOCK);
    }

    @Test
    @DisplayName("when清理，then新闻cutoff=now-90天、绑定码cutoff=now-1天、验证码cutoff=now-90天")
    void whenCleanup_thenCutoffsAreNowMinus90dAndNowMinus1d() {
        service.cleanupNow();

        verify(newsRepository).deleteRawBefore(NOW.minus(Duration.ofDays(90)));
        verify(bindingCodeRepository).deleteExpiredBefore(NOW.minus(Duration.ofDays(1)));
        verify(codeRepository).deleteCreatedBefore(NOW.minus(Duration.ofDays(90)));
    }

    @Test
    @DisplayName("给定新闻清理抛异常，when清理，then异常不外抛且绑定码清扫仍执行")
    void givenNewsCleanupBlowsUp_whenCleanup_thenBindingCodesStillSwept() {
        doThrow(new IllegalStateException("db down")).when(newsRepository).deleteRawBefore(any());

        assertThatCode(() -> service.cleanupNow()).doesNotThrowAnyException();

        verify(bindingCodeRepository).deleteExpiredBefore(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("给定验证码清理抛异常，when清理，then新闻与绑定码两步已生效且异常不外抛")
    void givenCodeCleanupBlowsUp_whenCleanup_thenNewsAndBindingStillSwept() {
        doThrow(new IllegalStateException("db down")).when(codeRepository).deleteCreatedBefore(any());

        assertThatCode(() -> service.cleanupNow()).doesNotThrowAnyException();

        verify(newsRepository).deleteRawBefore(NOW.minus(Duration.ofDays(90)));
        verify(bindingCodeRepository).deleteExpiredBefore(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("给定三步全炸，when调度入口，then顶层吞异常不炸调度线程")
    void givenAllStepsBlowUp_whenScheduled_thenSwallowed() {
        doThrow(new IllegalStateException("db down")).when(newsRepository).deleteRawBefore(any());
        doThrow(new IllegalStateException("db down"))
                .when(bindingCodeRepository).deleteExpiredBefore(any());
        doThrow(new IllegalStateException("db down"))
                .when(codeRepository).deleteCreatedBefore(any());

        assertThatCode(() -> service.cleanupScheduled()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("给定clock前移1小时，when清理，then各cutoff同步前移（无陈旧now）")
    void whenClockShifts_thenCutoffsTrackClock() {
        Instant later = NOW.plus(Duration.ofHours(1));
        IntelligenceCleanupService shifted = new IntelligenceCleanupService(
                newsRepository, bindingCodeRepository, codeRepository,
                Clock.fixed(later, ZoneId.of("Asia/Shanghai")));

        shifted.cleanupNow();

        verify(newsRepository).deleteRawBefore(later.minus(Duration.ofDays(90)));
        verify(bindingCodeRepository).deleteExpiredBefore(later.minus(Duration.ofDays(1)));
        verify(codeRepository).deleteCreatedBefore(later.minus(Duration.ofDays(90)));
        verify(newsRepository, never()).deleteRawBefore(NOW.minus(Duration.ofDays(90)));
    }
}
