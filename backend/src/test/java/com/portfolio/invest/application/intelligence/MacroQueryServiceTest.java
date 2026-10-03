package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.intelligence.MacroPoint;
import com.portfolio.invest.domain.intelligence.MacroRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MacroQueryService 夹紧与透传：series limit 夹紧 1..60、calendarUpcoming days 夹紧
 * 1..30 且窗口 today→today+days（Asia/Shanghai 时区、两端闭）、calendarOn 单日区间、
 * latest/recordSourceSwitch/lastSourceSwitchAt 纯委托仓库——夹紧单点收口在本服务，
 * T6 工具层与 P4 web 层不各自防御（照 IntelligenceQueryService 先例）。
 */
class MacroQueryServiceTest {

    /** 固定时钟：上海 2026-10-03 10:15（周六——日历口径按自然日不剔除非交易日）。 */
    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-03T02:15:00Z"), ZoneId.of("Asia/Shanghai"));

    private final MacroRepository repository = mock(MacroRepository.class);
    private MacroQueryService service;

    @BeforeEach
    void setUp() {
        service = new MacroQueryService(repository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("给定越界 limit，when查序列，then夹紧 1..60 后委托仓库并原样返回")
    void givenOutOfRangeLimit_whenSeries_thenClampedAndDelegated() {
        when(repository.findSeries(any(), eq(1))).thenReturn(List.of(cpiPoint()));
        when(repository.findSeries(any(), eq(60))).thenReturn(List.of());

        assertThat(service.series("CPI", 0)).containsExactly(cpiPoint()); // 0 → 1
        verify(repository).findSeries("CPI", 1);
        service.series("CPI", 100); // 100 → 60
        verify(repository).findSeries("CPI", 60);
        service.series("CPI", 30); // 界内原样透传
        verify(repository).findSeries("CPI", 30);
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("给定越界 days，when查 upcoming 日历，then夹紧 1..30 且窗口 today→today+days 两端闭")
    void givenOutOfRangeDays_whenCalendarUpcoming_thenClampedWindowFromTodayInclusive() {
        service.calendarUpcoming(0); // 0 → 1：today..today+1（两端闭）
        verify(repository).findCalendarBetween(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 4));
        service.calendarUpcoming(99); // 99 → 30：today..today+30
        verify(repository).findCalendarBetween(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 11, 2));
        service.calendarUpcoming(7); // 界内：today..today+7
        verify(repository).findCalendarBetween(LocalDate.of(2026, 10, 3), LocalDate.of(2026, 10, 10));
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("给定具体日期，when查当日日历，then区间两端同为该日")
    void givenDate_whenCalendarOn_thenSingleDayRange() {
        service.calendarOn(LocalDate.of(2026, 10, 9));
        verify(repository).findCalendarBetween(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 9));
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("given仓库返回，when latest/lastSourceSwitchAt，then原样透传")
    void givenRepositoryReturns_whenLatestAndLastSwitchAt_thenPassedThrough() {
        when(repository.findLatestPerIndicator()).thenReturn(List.of(cpiPoint()));
        when(repository.lastSourceSwitchAt("AFMI")).thenReturn(Optional.of(Instant.parse("2026-09-15T01:00:00Z")));

        assertThat(service.latest()).containsExactly(cpiPoint());
        assertThat(service.lastSourceSwitchAt("AFMI")).contains(Instant.parse("2026-09-15T01:00:00Z"));
        verify(repository).findLatestPerIndicator();
        verify(repository).lastSourceSwitchAt("AFMI");
        verifyNoMoreInteractions(repository);
    }

    @Test
    @DisplayName("given源切换四参，when记录留痕，then四参原序委托 insertSourceSwitch")
    void givenSwitchParams_whenRecordSourceSwitch_thenDelegatedWithExactArgs() {
        service.recordSourceSwitch("AFMI", "eastmoney", "pboc", "社融口径停更切换央行");

        verify(repository).insertSourceSwitch("AFMI", "eastmoney", "pboc", "社融口径停更切换央行");
        verifyNoMoreInteractions(repository);
    }

    private static MacroPoint cpiPoint() {
        return new MacroPoint("CPI", "2026-09", "MONTH",
                new BigDecimal("102.3"), new BigDecimal("0.6"), null, null);
    }
}
