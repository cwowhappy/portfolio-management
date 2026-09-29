package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.portfolio.invest.domain.intelligence.BindingCodeRepository;
import com.portfolio.invest.domain.intelligence.NewsRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 情报数据滚动清理切片：90 天/1 天两个 cutoff 口径（clock 注入算死）、新闻一步失败
 * 不挡绑定码一步、两步全炸时调度入口顶层吞异常不炸调度线程。
 */
class IntelligenceCleanupServiceTest {

    /** 固定时刻（上海 2026-09-29 04:07 → UTC 前一晚 20:07）。 */
    private static final Instant NOW = Instant.parse("2026-09-28T20:07:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));

    private final NewsRepository newsRepository = mock(NewsRepository.class);
    private final BindingCodeRepository bindingCodeRepository = mock(BindingCodeRepository.class);
    private IntelligenceCleanupService service;

    @BeforeEach
    void setUp() {
        service = new IntelligenceCleanupService(newsRepository, bindingCodeRepository, CLOCK);
    }

    @Test
    @DisplayName("when清理，then新闻cutoff=now-90天、绑定码cutoff=now-1天，两仓库各扫一次")
    void whenCleanup_thenCutoffsAreNowMinus90dAndNowMinus1d() {
        service.cleanupNow();

        verify(newsRepository).deleteRawBefore(NOW.minus(Duration.ofDays(90)));
        verify(bindingCodeRepository).deleteExpiredBefore(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("给定新闻清理抛异常，when清理，then异常不外抛且绑定码清扫仍执行")
    void givenNewsCleanupBlowsUp_whenCleanup_thenBindingCodesStillSwept() {
        doThrow(new IllegalStateException("db down")).when(newsRepository).deleteRawBefore(any());

        assertThatCode(() -> service.cleanupNow()).doesNotThrowAnyException();

        verify(bindingCodeRepository).deleteExpiredBefore(NOW.minus(Duration.ofDays(1)));
    }

    @Test
    @DisplayName("给定两步全炸，when调度入口，then顶层吞异常不炸调度线程")
    void givenBothStepsBlowUp_whenScheduled_thenSwallowed() {
        doThrow(new IllegalStateException("db down")).when(newsRepository).deleteRawBefore(any());
        doThrow(new IllegalStateException("db down"))
                .when(bindingCodeRepository).deleteExpiredBefore(any());

        assertThatCode(() -> service.cleanupScheduled()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("给定clock前移1小时，when清理，then两cutoff同步前移（无陈旧now）")
    void whenClockShifts_thenCutoffsTrackClock() {
        Instant later = NOW.plus(Duration.ofHours(1));
        IntelligenceCleanupService shifted = new IntelligenceCleanupService(
                newsRepository, bindingCodeRepository, Clock.fixed(later, ZoneId.of("Asia/Shanghai")));

        shifted.cleanupNow();

        verify(newsRepository).deleteRawBefore(later.minus(Duration.ofDays(90)));
        verify(bindingCodeRepository).deleteExpiredBefore(later.minus(Duration.ofDays(1)));
        verify(newsRepository, never()).deleteRawBefore(NOW.minus(Duration.ofDays(90)));
    }
}
