package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.config.InvestProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ConfidenceScorer 单元测试（MS-29 B7，设计规格 §4.2 步骤 8 + 决策 #3 参数组⑤）：
 * 四类机制信号各自的触发/不触发边界——比例恰 0.30、未懂数恰 3、陈旧恰 1/110/35 自然日、
 * 失败 0 次、修正 0 次；时钟注入构造 asOf 判定「今日」（禁真实 sleep）。
 * 口径：豁免数字已在 stats 之外（B5 排除），本类只消费 stats/锚定/真值池计数。
 */
class ConfidenceScorerTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 固定「今日」= 2026-10-06（与生产装配同时区 Asia/Shanghai）。 */
    private final ConfidenceScorer scorer = new ConfidenceScorer(
            new InvestProperties().getTrust().getConfidence(),
            Clock.fixed(Instant.parse("2026-10-06T04:00:00Z"), ZONE));

    private static AnchorRecord anchor(String tool, String asOf, ToolInvocation.AsOfKind kind) {
        return new AnchorRecord("1520.33", 1, TrustVerdict.VERIFIED, tool, Map.of("code", "600519"),
                asOf, kind, "1520.33");
    }

    private static AnchorRecord unverifiedAnchor() {
        return new AnchorRecord("3.2%", 1, TrustVerdict.UNVERIFIED, null, null, null, null, null);
    }

    private static ToolInvocation failedCall() {
        return new ToolInvocation("get_quote", Map.of("code", "600519"), "", List.of(),
                "2026-10-06 09:30:00", ToolInvocation.AsOfKind.CALL, true, false, 0L);
    }

    private List<ConfidenceSignal.Hit> score(List<AnchorRecord> anchors, AnchorBatch.Stats stats,
            List<ToolInvocation> pool, int corrections, int correctionFailures) {
        return scorer.score(anchors, stats, pool, corrections, correctionFailures);
    }

    // ———— 未溯源比例（口径分母=数据性锚定数；豁免数字不入分子分母——B5 stats 已排除） ————

    @DisplayName("比例恰 0.30（3/10）不触发；未懂数恰 3 且比例超线触发")
    @Test
    void givenUnverifiedRatioBoundaries_whenScore_thenRatioSignalOnlyBeyondThreshold() {
        // 恰 0.30：3/10 → 不触发（严格大于）
        assertThat(score(List.of(), new AnchorBatch.Stats(7, 0, 3), List.of(), 0, 0))
                .doesNotContain(new ConfidenceSignal.Hit(ConfidenceSignal.UNVERIFIED_RATIO, 0.3));

        // 未懂数恰 3（=unverified-min）且 3/4=0.75 > 0.30 → 触发，metric=0.75
        assertThat(score(List.of(), new AnchorBatch.Stats(1, 0, 3), List.of(), 0, 0))
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.UNVERIFIED_RATIO, 0.75));
    }

    @DisplayName("比例超线但未懂数不足 3（2/4=0.5）不触发；零锚定不触发")
    @Test
    void givenUnverifiedBelowMin_whenScore_thenNoRatioSignal() {
        assertThat(score(List.of(), new AnchorBatch.Stats(2, 0, 2), List.of(), 0, 0)).isEmpty();
        assertThat(score(List.of(), new AnchorBatch.Stats(0, 0, 0), List.of(), 0, 0)).isEmpty();
    }

    // ———— 陈旧度（按数据类别；asOfKind=GENERATED/CALL 不参与；不可解析保守跳过） ————

    @DisplayName("行情族边界：恰 1 自然日不触发，超过 1 自然日触发（quote/kline/overview 归族）")
    @Test
    void givenQuoteAsOfBoundaries_whenScore_thenStaleQuotesBeyondOneDay() {
        // 恰 1 自然日（2026-10-05）：不触发
        assertThat(score(List.of(anchor("get_quote", "2026-10-05 09:30:00",
                        ToolInvocation.AsOfKind.DATA)),
                new AnchorBatch.Stats(1, 0, 0), List.of(), 0, 0)).isEmpty();
        // 2 自然日（2026-10-04）：触发
        assertThat(score(List.of(anchor("get_quote", "2026-10-04 09:30:00",
                        ToolInvocation.AsOfKind.DATA)),
                new AnchorBatch.Stats(1, 0, 0), List.of(), 0, 0))
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_QUOTES, 1));
        // kline/overview 同族：两个陈旧锚定 → stale_quotes:2
        assertThat(score(List.of(
                        anchor("get_kline", "2026-10-03", ToolInvocation.AsOfKind.DATA),
                        anchor("get_market_overview", "2026-10-02 15:00:00",
                                ToolInvocation.AsOfKind.DATA)),
                new AnchorBatch.Stats(2, 0, 0), List.of(), 0, 0))
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_QUOTES, 2));
    }

    @DisplayName("财报族边界：恰 110 天不触发，111 天触发（get_financials/analyze_financials 的 reportDate）")
    @Test
    void givenFinancialReportDateBoundaries_whenScore_thenStaleFinancialsBeyond110Days() {
        assertThat(score(List.of(anchor("get_financials", "2026-06-18",
                        ToolInvocation.AsOfKind.DATA)),
                new AnchorBatch.Stats(1, 0, 0), List.of(), 0, 0)).isEmpty();
        assertThat(score(List.of(anchor("analyze_financials", "2026-06-17",
                        ToolInvocation.AsOfKind.DATA)),
                new AnchorBatch.Stats(1, 0, 0), List.of(), 0, 0))
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_FINANCIALS, 1));
    }

    @DisplayName("宏观族边界：恰 35 天不触发，36 天触发；月度期别 yyyy-MM 按月初折算")
    @Test
    void givenMacroPeriodBoundaries_whenScore_thenStaleMacroBeyond35Days() {
        // 日度期别恰 35 天：不触发
        assertThat(score(List.of(anchor("macro_brief", "2026-09-01",
                        ToolInvocation.AsOfKind.DATA)),
                new AnchorBatch.Stats(1, 0, 0), List.of(), 0, 0)).isEmpty();
        // 日度期别 36 天：触发
        assertThat(score(List.of(anchor("macro_brief", "2026-08-31",
                        ToolInvocation.AsOfKind.DATA)),
                new AnchorBatch.Stats(1, 0, 0), List.of(), 0, 0))
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_MACRO, 1));
        // 月度期别 2026-08（折算 2026-08-01，66 天）：触发
        assertThat(score(List.of(anchor("macro_brief", "2026-08",
                        ToolInvocation.AsOfKind.DATA)),
                new AnchorBatch.Stats(1, 0, 0), List.of(), 0, 0))
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_MACRO, 1));
    }

    @DisplayName("GENERATED/CALL 锚定不参与陈旧度；未归族工具与不可解析 asOf 保守跳过")
    @Test
    void givenGeneratedCallOrUnknownToolOrUnparseableAsOf_whenScore_thenNoStaleSignal() {
        assertThat(score(List.of(
                        anchor("get_market_overview", "2026-01-01 12:00:00",
                                ToolInvocation.AsOfKind.GENERATED),
                        anchor("mcp_tool", "2026-01-01 12:00:00", ToolInvocation.AsOfKind.CALL),
                        anchor("get_news", "2026-01-01", ToolInvocation.AsOfKind.DATA),
                        anchor("get_quote", "不是日期", ToolInvocation.AsOfKind.DATA),
                        anchor("get_quote", null, ToolInvocation.AsOfKind.DATA)),
                new AnchorBatch.Stats(5, 0, 0), List.of(), 0, 0)).isEmpty();
    }

    // ———— 工具失败（本回合真值池 failed=true 调用数 ≥ 1） ————

    @DisplayName("失败 0 次不触发；2 次 failed=true 调用 → tool_failures:2")
    @Test
    void givenFailedInvocationCount_whenScore_thenToolFailuresSignalAtLeastOne() {
        ToolInvocation ok = new ToolInvocation("get_quote", Map.of(), "{\"price\":1520.33}",
                List.of(), "2026-10-06 09:30:00", ToolInvocation.AsOfKind.DATA, false, false, 0L);
        assertThat(score(List.of(), new AnchorBatch.Stats(1, 0, 0), List.of(ok), 0, 0)).isEmpty();
        assertThat(score(List.of(), new AnchorBatch.Stats(1, 0, 0),
                List.of(failedCall(), failedCall()), 0, 0))
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.TOOL_FAILURES, 2));
        // 池 null 同 0 次（护栏）
        assertThat(score(List.of(), new AnchorBatch.Stats(1, 0, 0), null, 0, 0)).isEmpty();
    }

    // ———— 修正（corrections 含修正失败计数；correction_failed 单列） ————

    @DisplayName("修正 0 次不触发；corrections:2 且失败 1 次时单列 correction_failed:1")
    @Test
    void givenCorrectionCounts_whenScore_thenCorrectionsAndFailureSignals() {
        assertThat(score(List.of(), new AnchorBatch.Stats(1, 0, 0), List.of(), 0, 0)).isEmpty();
        assertThat(score(List.of(), new AnchorBatch.Stats(1, 0, 0), List.of(), 0, 1))
                .containsExactly(new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTION_FAILED, 1));
        assertThat(score(List.of(), new AnchorBatch.Stats(1, 0, 0), List.of(), 2, 1))
                .containsExactly(
                        new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTIONS, 2),
                        new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTION_FAILED, 1));
    }

    // ———— 组合与顺序（固定序：ratio → 陈旧三类 → failures → corrections → correction_failed） ————

    @DisplayName("多信号并发：按固定线序输出（简单可解释，v1 无加权组合）")
    @Test
    void givenMultipleSignals_whenScore_thenEmittedInCanonicalOrder() {
        List<AnchorRecord> anchors = List.of(
                unverifiedAnchor(), unverifiedAnchor(), unverifiedAnchor(),
                anchor("get_quote", "2026-10-01", ToolInvocation.AsOfKind.DATA));

        assertThat(score(anchors, new AnchorBatch.Stats(1, 0, 3), List.of(failedCall()), 2, 1))
                .containsExactly(
                        new ConfidenceSignal.Hit(ConfidenceSignal.UNVERIFIED_RATIO, 0.75),
                        new ConfidenceSignal.Hit(ConfidenceSignal.STALE_QUOTES, 1),
                        new ConfidenceSignal.Hit(ConfidenceSignal.TOOL_FAILURES, 1),
                        new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTIONS, 2),
                        new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTION_FAILED, 1));
    }

    // ———— wire 形态（payload v1 confidence.signals 条目：线名:值） ————

    @DisplayName("Hit wire 形态：比例两位小数去尾零（0.75/0.4），计数整数")
    @Test
    void givenHits_whenWire_thenSnakeNameWithCompactValue() {
        assertThat(new ConfidenceSignal.Hit(ConfidenceSignal.UNVERIFIED_RATIO, 0.75).wire())
                .isEqualTo("unverified_ratio:0.75");
        assertThat(new ConfidenceSignal.Hit(ConfidenceSignal.UNVERIFIED_RATIO, 0.4).wire())
                .isEqualTo("unverified_ratio:0.4");
        assertThat(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_QUOTES, 2).wire())
                .isEqualTo("stale_quotes:2");
        assertThat(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_FINANCIALS, 1).wire())
                .isEqualTo("stale_financials:1");
        assertThat(new ConfidenceSignal.Hit(ConfidenceSignal.STALE_MACRO, 1).wire())
                .isEqualTo("stale_macro:1");
        assertThat(new ConfidenceSignal.Hit(ConfidenceSignal.TOOL_FAILURES, 3).wire())
                .isEqualTo("tool_failures:3");
        assertThat(new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTIONS, 2).wire())
                .isEqualTo("corrections:2");
        assertThat(new ConfidenceSignal.Hit(ConfidenceSignal.CORRECTION_FAILED, 1).wire())
                .isEqualTo("correction_failed:1");
    }
}
