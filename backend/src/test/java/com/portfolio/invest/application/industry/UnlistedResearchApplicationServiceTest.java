package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.FundingEvent;
import com.portfolio.invest.domain.industry.FundingEventRepository;
import com.portfolio.invest.domain.industry.FundingRound;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.UnlistedCompany;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 未上市读聚合服务单测（Mockito 构造注入，照 IndustryApplicationServiceTest 先例）：
 * 三方法均先行业白名单校验；months ∈ [1,60]；overview 聚合口径（§七：市值亿=成员
 * total_mv 求和/1e8、12 月窗口=minusDays(365)、轮次分布按 FundingRound.order() 排序且
 * 零计数轮次不透出）。
 */
class UnlistedResearchApplicationServiceTest {

    private final UnlistedCompanyRepository companyRepository = mock(UnlistedCompanyRepository.class);
    private final FundingEventRepository eventRepository = mock(FundingEventRepository.class);
    private final IndustryRepository industryRepository = mock(IndustryRepository.class);
    private final UnlistedResearchApplicationService service = new UnlistedResearchApplicationService(
            companyRepository, eventRepository, industryRepository);

    private static UnlistedCompany company(String name, FundingRound round, LocalDate lastDate, BigDecimal totalYi) {
        return new UnlistedCompany(1L, "801080", name, "半导体设备", round, lastDate, totalYi,
                "一句话简介", "示例来源", Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static FundingEvent event(LocalDate date, String companyName, FundingRound round) {
        return new FundingEvent(1L, date, companyName, round, new BigDecimal("3.20"), "高瓴、红杉",
                "801080", "半导体设备", "睿兽分析月报", null, Instant.parse("2026-09-01T00:00:00Z"));
    }

    @DisplayName("三读方法行业不存在一律抛 INDUSTRY_NOT_FOUND")
    @Test
    void givenUnknownIndustry_whenAnyRead_thenThrowNotFound() {
        when(industryRepository.existsIndustry("999999")).thenReturn(false);

        assertThat(catchThrowableOfType(() -> service.companies("999999"), IndustryException.class)
                .code()).isEqualTo(IndustryErrorCode.INDUSTRY_NOT_FOUND);
        assertThat(catchThrowableOfType(() -> service.fundingEvents("999999", 24), IndustryException.class)
                .code()).isEqualTo(IndustryErrorCode.INDUSTRY_NOT_FOUND);
        assertThat(catchThrowableOfType(() -> service.overview("999999"), IndustryException.class)
                .code()).isEqualTo(IndustryErrorCode.INDUSTRY_NOT_FOUND);
    }

    @DisplayName("companies 透出轮次双字段与可空字段")
    @Test
    void givenCuratedCompanies_whenCompanies_thenReturnViewsWithDualRoundFields() {
        when(industryRepository.existsIndustry("801080")).thenReturn(true);
        when(companyRepository.findByIndustry("801080")).thenReturn(List.of(
                company("示例华芯科技", FundingRound.B, LocalDate.of(2026, 6, 15), new BigDecimal("12.50")),
                company("示例未名科技", FundingRound.UNKNOWN, null, null)));

        List<UnlistedCompanyView> views = service.companies("801080");

        assertThat(views).hasSize(2);
        UnlistedCompanyView first = views.get(0);
        assertThat(first.latestRound()).isEqualTo("B");
        assertThat(first.latestRoundLabel()).isEqualTo("B轮");
        assertThat(first.totalFundingYi()).isEqualByComparingTo("12.50");
        UnlistedCompanyView unknown = views.get(1);
        assertThat(unknown.latestRound()).isEqualTo("UNKNOWN");
        assertThat(unknown.latestRoundLabel()).isEqualTo("未知");
        assertThat(unknown.lastFundingDate()).isNull();
        assertThat(unknown.totalFundingYi()).isNull();
    }

    @DisplayName("months 越界（0/61）抛 INVALID_LIMIT 且不查仓储")
    @Test
    void givenMonthsOutOfRange_whenFundingEvents_thenThrowInvalidLimit() {
        when(industryRepository.existsIndustry("801080")).thenReturn(true);

        assertThat(catchThrowableOfType(() -> service.fundingEvents("801080", 0), IndustryException.class)
                .code()).isEqualTo(IndustryErrorCode.INVALID_LIMIT);
        assertThat(catchThrowableOfType(() -> service.fundingEvents("801080", 61), IndustryException.class)
                .code()).isEqualTo(IndustryErrorCode.INVALID_LIMIT);

        verify(eventRepository, never()).findByIndustrySince(anyString(), any());
    }

    @DisplayName("fundingEvents 按月数窗口查询并透出轮次双字段")
    @Test
    void givenValidMonths_whenFundingEvents_thenQuerySinceWindowAndMapViews() {
        when(industryRepository.existsIndustry("801080")).thenReturn(true);
        when(eventRepository.findByIndustrySince(eq("801080"), any())).thenReturn(List.of(
                event(LocalDate.of(2026, 6, 15), "示例华芯科技", FundingRound.B),
                event(LocalDate.of(2026, 2, 10), "示例光子科技", FundingRound.D)));

        List<FundingEventView> views = service.fundingEvents("801080", 24);

        // 窗口口径：since = today.minusMonths(months)（近 N 月）
        verify(eventRepository).findByIndustrySince("801080", LocalDate.now().minusMonths(24));
        assertThat(views).hasSize(2);
        assertThat(views.get(0).round()).isEqualTo("B");
        assertThat(views.get(0).roundLabel()).isEqualTo("B轮");
        assertThat(views.get(1).round()).isEqualTo("D");
        assertThat(views.get(1).roundLabel()).isEqualTo("D轮");
        assertThat(views.get(0).sourceTitle()).isEqualTo("睿兽分析月报");
        assertThat(views.get(0).sourceUrl()).isNull();
    }

    @DisplayName("overview 聚合：上市市值亿求和、策展计数、12 月窗口事件数与轮次分布排序")
    @Test
    void givenOverviewInputs_whenOverview_thenAggregate() {
        when(industryRepository.existsIndustry("801080")).thenReturn(true);
        when(industryRepository.findIndustryStocks("801080", "total_mv", "DESC", 1000)).thenReturn(List.of(
                new IndustryStock("601988", "中国银行", new BigDecimal("2500000000000"), null, null, null, null, null, null, null),
                new IndustryStock("601288", "农业银行", new BigDecimal("180000000000"), null, null, null, null, null, null, null),
                new IndustryStock("300999", "无市值新股", null, null, null, null, null, null, null, null)));
        when(companyRepository.countByIndustry("801080")).thenReturn(5L);
        // 12 月窗口内：B×2、A×1、D×1（乱序给出，断言按 order() 排序且零计数轮次不透出）
        when(eventRepository.findByIndustrySince(eq("801080"), any())).thenReturn(List.of(
                event(LocalDate.of(2026, 6, 15), "示例华芯科技", FundingRound.B),
                event(LocalDate.of(2025, 9, 10), "示例华芯科技", FundingRound.A),
                event(LocalDate.of(2026, 4, 20), "示例纳微科技", FundingRound.D),
                event(LocalDate.of(2026, 5, 1), "示例纳微科技", FundingRound.B)));

        UnlistedOverviewView overview = service.overview("801080");

        // 12 月窗口口径：since = today.minusDays(365)
        verify(eventRepository).findByIndustrySince("801080", LocalDate.now().minusDays(365));
        assertThat(overview.listedCount()).isEqualTo(3);
        // (2.5e12 + 1.8e11) 元 / 1e8 = 26800.00 亿元；null 市值成员不计入求和但计入家数
        assertThat(overview.listedMarketCapYi()).isEqualByComparingTo("26800.00");
        assertThat(overview.curatedCount()).isEqualTo(5);
        assertThat(overview.fundingEvents12m()).isEqualTo(4);
        assertThat(overview.roundDistribution())
                .containsExactly(new UnlistedOverviewView.RoundCount("A", 1),
                        new UnlistedOverviewView.RoundCount("B", 2),
                        new UnlistedOverviewView.RoundCount("D", 1));
        assertThat(overview.coverageNote()).isEqualTo("策展名单与月度摘录融资事件，非全量口径");
    }
}
