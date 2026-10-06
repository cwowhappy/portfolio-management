package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.intelligence.IntelligenceQueryService;
import com.portfolio.invest.application.intelligence.MacroBriefFilter;
import com.portfolio.invest.application.market.FinancialQueryService;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * macro_brief 只读工具（MS-22 Task 6）：JSON 输出契约（indicators/policies/missing/generatedAt）、
 * TY 两点无历史的 note 字段翻译（T4 裁定随行——勿当数据缺失）、空政策 policies 空数组 + note、
 * 缺失指标显式列出（F13 缺失不编造不省略）、参数透传（解析与夹紧归 IntelligenceQueryService）
 * 与 run() 兜底不抛。
 */
class MacroBriefToolTest {

    /** TY 无历史序列 note（T4 裁定：最新点合成无历史序列，note 字段交代而非空数组）。 */
    private static final String TY_NO_HISTORY_NOTE = "国债收益率仅最新点、无历史序列";

    /** 空政策 note（政策库长期保留，窗口内无命中非「无数据」）。 */
    private static final String POLICY_EMPTY_NOTE = "该窗口内暂无政策事件（可调大 policyDays 或稍后再试）";

    private IntelligenceQueryService intelligenceQuery;
    private InvestTools tools;

    @BeforeEach
    void setUp() {
        intelligenceQuery = mock(IntelligenceQueryService.class);
        // 复刻 Spring Boot 对 ObjectMapper 的配置（JavaTimeModule + ISO 日期，不写时间戳）
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        tools = new InvestTools(
                mock(MarketDataService.class),
                mock(ValuationApplicationService.class),
                mock(ScreeningApplicationService.class),
                mock(FinancialQueryService.class),
                mock(IndustryApplicationService.class),
                intelligenceQuery,
                mapper,
                new com.portfolio.invest.config.InvestProperties());
    }

    /** 月度指标条目（近 5 期序列最新在前）。 */
    private static IntelligenceQueryService.MacroIndicatorView cpiView() {
        return new IntelligenceQueryService.MacroIndicatorView("CPI",
                new BigDecimal("0.6"), new BigDecimal("0.6"), "2026-09", "MONTH",
                List.of(new IntelligenceQueryService.SeriesPoint("2026-09", new BigDecimal("0.6")),
                        new IntelligenceQueryService.SeriesPoint("2026-08", new BigDecimal("0.5")),
                        new IntelligenceQueryService.SeriesPoint("2026-07", new BigDecimal("0.4"))));
    }

    /** 国债收益率条目（series 恒空——跨表只合成最新点，工具层翻译 note）。 */
    private static IntelligenceQueryService.MacroIndicatorView ty1yView() {
        return new IntelligenceQueryService.MacroIndicatorView("TY1Y",
                new BigDecimal("1.45"), null, "2026-09-30", "DAY", List.of());
    }

    private static IntelligenceQueryService.PolicyItemView policy(String title, PolicyDirection direction) {
        return new IntelligenceQueryService.PolicyItemView(title, direction, PolicyStrength.HIGH,
                List.of("房地产", "基建"), "降准 0.5 个百分点释放长期资金约 1 万亿元",
                PolicyConfidence.HIGH, true, "https://x/p1",
                Instant.parse("2026-09-28T09:30:00Z"));
    }

    @DisplayName("正常结果：indicators 每项带期别 + policies 条目九字段 + total + generatedAt")
    @Test
    void givenFullResult_whenMacroBrief_thenJsonHasIndicatorsPoliciesAndGeneratedAt() {
        when(intelligenceQuery.macroBrief(any())).thenReturn(new IntelligenceQueryService.MacroBriefResult(
                List.of(cpiView()), List.of(policy("央行降准", PolicyDirection.EASING)), 12,
                List.of(), Instant.parse("2026-10-03T02:15:00Z")));

        String json = tools.macroBrief(null, null);

        assertThat(json)
                .contains("\"indicator\":\"CPI\"").contains("\"value\":0.6")
                .contains("\"period\":\"2026-09\"").contains("\"periodType\":\"MONTH\"")
                .contains("\"series\":[{\"period\":\"2026-09\",\"value\":0.6}")
                .contains("\"policies\":[")
                .contains("央行降准")
                .contains("\"direction\":\"EASING\"").contains("\"strength\":\"HIGH\"")
                .contains("\"areas\":[\"房地产\",\"基建\"]")
                .contains("降准 0.5 个百分点").contains("\"confidence\":\"HIGH\"")
                .contains("\"isPolicy\":true").contains("https://x/p1")
                .contains("2026-09-28T09:30:00Z")
                .contains("\"total\":12")
                .contains("\"generatedAt\":\"2026-10-03T02:15:00Z\"")
                .contains("\"missing\":[]")
                .doesNotContain(POLICY_EMPTY_NOTE)
                .doesNotContain(TY_NO_HISTORY_NOTE);
    }

    @DisplayName("TY 无历史：series 空翻译为 note 字段，不输出空数组（勿当数据缺失）")
    @Test
    void givenTyWithoutHistory_whenMacroBrief_thenNoteFieldInsteadOfEmptySeries() {
        when(intelligenceQuery.macroBrief(any())).thenReturn(new IntelligenceQueryService.MacroBriefResult(
                List.of(cpiView(), ty1yView()), List.of(), 0, List.of(),
                Instant.parse("2026-10-03T02:15:00Z")));

        String json = tools.macroBrief("CPI,TY1Y", null);

        assertThat(json)
                .contains("\"indicator\":\"TY1Y\"").contains("\"value\":1.45")
                .contains("\"period\":\"2026-09-30\"")
                .contains("\"note\":\"" + TY_NO_HISTORY_NOTE + "\"")
                .doesNotContain("\"series\":[]");
    }

    @DisplayName("缺失指标显式列出：missing 数组带 {indicator,missing:true}，不编造值")
    @Test
    void givenMissingIndicator_whenMacroBrief_thenExplicitMissingEntryWithoutFabrication() {
        when(intelligenceQuery.macroBrief(any())).thenReturn(new IntelligenceQueryService.MacroBriefResult(
                List.of(cpiView()), List.of(), 0, List.of("PMI"),
                Instant.parse("2026-10-03T02:15:00Z")));

        String json = tools.macroBrief("CPI,PMI", null);

        assertThat(json)
                .contains("\"missing\":[{\"indicator\":\"PMI\",\"missing\":true}]")
                .doesNotContain("\"indicator\":\"PMI\",\"value\""); // 缺失指标不进 indicators 数组（无编造值）
    }

    @DisplayName("空政策：policies 空数组 + note，不带 total")
    @Test
    void givenNoPolicies_whenMacroBrief_thenEmptyArrayWithNote() {
        when(intelligenceQuery.macroBrief(any())).thenReturn(new IntelligenceQueryService.MacroBriefResult(
                List.of(cpiView()), List.of(), 0, List.of(),
                Instant.parse("2026-10-03T02:15:00Z")));

        String json = tools.macroBrief(null, 7);

        assertThat(json)
                .contains("\"policies\":[]")
                .contains("\"note\":\"" + POLICY_EMPTY_NOTE + "\"")
                .doesNotContain("\"total\"");
    }

    @DisplayName("参数透传：indicators/policyDays 原样组装过滤器（解析与夹紧归服务层）")
    @Test
    void givenParams_whenMacroBrief_thenFilterPassedThrough() {
        when(intelligenceQuery.macroBrief(any())).thenReturn(new IntelligenceQueryService.MacroBriefResult(
                List.of(), List.of(), 0, List.of(), Instant.parse("2026-10-03T02:15:00Z")));

        tools.macroBrief("cpi, ty1y", 45);

        verify(intelligenceQuery).macroBrief(argThat(f ->
                "cpi, ty1y".equals(f.indicators()) && Integer.valueOf(45).equals(f.policyDays())));
    }

    @DisplayName("服务异常：run 兜底结构化错误，绝不抛")
    @Test
    void givenServiceBlowsUp_whenMacroBrief_thenStructuredErrorNotThrown() {
        when(intelligenceQuery.macroBrief(any())).thenThrow(new IllegalStateException("db down"));

        String json = tools.macroBrief(null, null);

        assertThat(json).contains("\"error\":\"工具执行失败\"").contains("\"hint\":\"请稍后重试\"");
    }
}
