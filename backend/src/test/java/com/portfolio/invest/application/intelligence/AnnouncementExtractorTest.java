package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.intelligence.IntelligenceChatPort.ChatOutcome;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 单条公告抽取纯组件（T9 eval runner 直调口径）：metrics 六字段契约（数值/未披露 null +
 * undisclosed 标注）、annTypes 并集去重与枚举过滤、围栏容错、解析失败重试 1 次、LLM 通道
 * 不可用与解析失败可区分、inputTokens 累计（D16 护栏输入）、提示词接线（标题 + PDF 文本
 * 截前 8000 字符）。公告无 importance 字段（区别于新闻抽取）。
 */
class AnnouncementExtractorTest {

    private static final String FULL_JSON = """
            {"metrics":{"revenueYi":128.56,"netProfitYi":31.2,"netProfitYoyPct":25.3,
            "deductedProfitYi":30.05,"grossMarginPct":91.5,"dividendDesc":"每10股派2元",
            "undisclosed":[]},"annTypes":["PERIODIC_REPORT","EQUITY_INCENTIVE"]}""";
    private static final String INVALID_JSON = "抱歉这不是 JSON{{{";

    private final AnnouncementExtractor extractor = new AnnouncementExtractor();

    @Test
    @DisplayName("给定合法完整JSON，when单条抽取，then六字段全量映射且annTypes并集去重")
    void givenFullValidJson_whenExtractOne_thenAllMetricsMappedWithUnion() {
        var outcome = extractor.extractOne(portReturning(FULL_JSON), "贵州茅台2026年半年度报告",
                "营业收入128.56亿元", List.of(AnnouncementType.PERIODIC_REPORT));

        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.SUCCESS);
        var metrics = outcome.fields().metrics();
        assertThat(metrics.revenueYi()).isEqualByComparingTo("128.56");
        assertThat(metrics.netProfitYi()).isEqualByComparingTo("31.2");
        assertThat(metrics.netProfitYoyPct()).isEqualByComparingTo("25.3");
        assertThat(metrics.deductedProfitYi()).isEqualByComparingTo("30.05");
        assertThat(metrics.grossMarginPct()).isEqualByComparingTo("91.5");
        assertThat(metrics.dividendDesc()).isEqualTo("每10股派2元");
        assertThat(metrics.undisclosed()).isEmpty();
        // 并集语义：源站栏目直判在前 + LLM 精判增补，重叠去重
        assertThat(outcome.fields().annTypes()).containsExactly(
                AnnouncementType.PERIODIC_REPORT, AnnouncementType.EQUITY_INCENTIVE);
        assertThat(outcome.inputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("给定部分字段未披露，when单条抽取，then未披露字段null且字段名进undisclosed")
    void givenPartialUndisclosed_whenExtractOne_thenNullFieldsListedInUndisclosed() {
        String json = """
                {"metrics":{"revenueYi":88.5,"netProfitYi":null,"netProfitYoyPct":-12.4,
                "deductedProfitYi":null,"grossMarginPct":null,"dividendDesc":null,
                "undisclosed":["归母净利润","扣非净利润","毛利率","分红"]},"annTypes":["EARNINGS_FORECAST"]}""";

        var outcome = extractor.extractOne(portReturning(json), "2026年度业绩预告",
                "预计净利润同比下降", List.of());

        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.SUCCESS);
        var metrics = outcome.fields().metrics();
        assertThat(metrics.revenueYi()).isEqualByComparingTo("88.5");
        assertThat(metrics.netProfitYi()).isNull();
        assertThat(metrics.netProfitYoyPct()).isEqualByComparingTo("-12.4");
        assertThat(metrics.deductedProfitYi()).isNull();
        assertThat(metrics.grossMarginPct()).isNull();
        assertThat(metrics.dividendDesc()).isNull();
        assertThat(metrics.undisclosed()).containsExactly("归母净利润", "扣非净利润", "毛利率", "分红");
        assertThat(outcome.fields().annTypes()).containsExactly(AnnouncementType.EARNINGS_FORECAST);
    }

    @Test
    @DisplayName("给定源站直判类型与LLM输出重叠交叉，when单条抽取，then并集去重且源站直判在前")
    void givenSourceAndLlmTypesOverlap_whenExtractOne_thenUnionDedupSourceFirst() {
        String json = "{\"metrics\":{},\"annTypes\":[\"PERIODIC_REPORT\",\"PLACEMENT\"]}";

        var outcome = extractor.extractOne(portReturning(json), "标题", "正文",
                List.of(AnnouncementType.PLACEMENT, AnnouncementType.OTHER));

        assertThat(outcome.fields().annTypes()).containsExactly(
                AnnouncementType.PLACEMENT, AnnouncementType.OTHER, AnnouncementType.PERIODIC_REPORT);
    }

    @Test
    @DisplayName("给定LLM输出非受控枚举名，when单条抽取，then剔除无效名不判失败（∩有效枚举）")
    void givenInvalidEnumNames_whenExtractOne_thenFilteredNotFailed() {
        String json = "{\"metrics\":{},\"annTypes\":[\"BUYBACK\",\"MAJOR_EVENT\",\"buyback\"]}";

        var outcome = extractor.extractOne(portReturning(json), "标题", "正文",
                List.of(AnnouncementType.BUYBACK));

        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.fields().annTypes()).containsExactly(AnnouncementType.BUYBACK);
    }

    @Test
    @DisplayName("给定模型违规加围栏，when单条抽取，then剥离围栏后仍解析成功")
    void givenFencedJson_whenExtractOne_thenFencesStrippedAndParsed() {
        String fenced = "```json\n" + FULL_JSON + "\n```";

        var outcome = extractor.extractOne(portReturning(fenced), "标题", "正文", List.of());

        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.fields().metrics().revenueYi()).isEqualByComparingTo("128.56");
    }

    @Test
    @DisplayName("给定空串或空白输出，when单条抽取，then视为解析失败")
    void givenBlankText_whenExtractOne_thenParseFailed() {
        assertThat(extractor.extractOne(portReturning("", ""), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);
        assertThat(extractor.extractOne(portReturning("   \n\t ", "   \n\t "), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("给定两次非法JSON，when单条抽取，then仅重试1次后标解析失败且两侧tokens累计")
    void givenAlwaysInvalid_whenExtractOne_thenParseFailedAfterSingleRetry() {
        var outcome = extractor.extractOne(portReturning(INVALID_JSON, INVALID_JSON, FULL_JSON),
                "标题", "正文", List.of());

        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);
        assertThat(outcome.fields()).isNull();
        assertThat(outcome.inputTokens()).isEqualTo(200); // 第 3 次调用不应发生
    }

    @Test
    @DisplayName("给定首抽非法JSON重试合法，when单条抽取，then重试救回且tokens累计")
    void givenInvalidThenValid_whenExtractOne_thenRescuedWithSummedTokens() {
        var outcome = extractor.extractOne(portReturning(INVALID_JSON, FULL_JSON), "标题", "正文", List.of());

        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.inputTokens()).isEqualTo(200);
    }

    @Test
    @DisplayName("给定LLM未配置，when单条抽取，then返回通道不可用（区别于解析失败）")
    void givenLlmUnavailable_whenExtractOne_thenLlmUnavailableOutcome() {
        var outcome = extractor.extractOne((s, u) -> Optional.empty(), "标题", "正文", List.of());

        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.LLM_UNAVAILABLE);
        assertThat(outcome.inputTokens()).isZero();
    }

    @Test
    @DisplayName("给定首抽解析失败重试遇LLM失效，when单条抽取，then按通道不可用处理且计首抽tokens")
    void givenInvalidThenLlmDown_whenExtractOne_thenLlmUnavailableWithFirstAttemptTokens() {
        var outcome = extractor.extractOne(portReturningThenEmpty(INVALID_JSON), "标题", "正文", List.of());

        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.LLM_UNAVAILABLE);
        assertThat(outcome.inputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("给定metrics缺失或非对象，when单条抽取，then视为解析失败（核心产出锚点）")
    void givenMissingOrNonObjectMetrics_whenExtractOne_thenParseFailed() {
        String missing = "{\"annTypes\":[\"BUYBACK\"]}";
        assertThat(extractor.extractOne(portReturning(missing, missing), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);

        String nullMetrics = "{\"metrics\":null,\"annTypes\":[]}";
        assertThat(extractor.extractOne(portReturning(nullMetrics, nullMetrics), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);

        String arrayMetrics = "{\"metrics\":[],\"annTypes\":[]}";
        assertThat(extractor.extractOne(portReturning(arrayMetrics, arrayMetrics), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("给定数值字段非数字文本，when单条抽取，then视为解析失败（严禁口径漂移）")
    void givenNonNumericMetricText_whenExtractOne_thenParseFailed() {
        String json = FULL_JSON.replace("128.56", "\"大幅增长\"");
        assertThat(extractor.extractOne(portReturning(json, json), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("给定数值字段为数字字符串，when单条抽取，then宽容接受为BigDecimal")
    void givenNumericStringMetric_whenExtractOne_thenAcceptedAsBigDecimal() {
        String json = FULL_JSON.replace("128.56", "\"128.56\"");
        var outcome = extractor.extractOne(portReturning(json), "标题", "正文", List.of());

        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.fields().metrics().revenueYi()).isEqualByComparingTo("128.56");
    }

    @Test
    @DisplayName("给定undisclosed或annTypes非数组，when单条抽取，then视为解析失败；缺失归一为空")
    void givenNonArrayListFields_whenExtractOne_thenParseFailedOrNormalized() {
        String badUndisclosed = FULL_JSON.replace("\"undisclosed\":[]", "\"undisclosed\":\"毛利率\"");
        assertThat(extractor.extractOne(portReturning(badUndisclosed, badUndisclosed), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);

        String badAnnTypes = "{\"metrics\":{},\"annTypes\":\"BUYBACK\"}";
        assertThat(extractor.extractOne(portReturning(badAnnTypes, badAnnTypes), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);

        String noLists = "{\"metrics\":{\"revenueYi\":1.0}}";
        var outcome = extractor.extractOne(portReturning(noLists), "标题", "正文", List.of());
        assertThat(outcome.status()).isEqualTo(AnnouncementExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.fields().metrics().undisclosed()).isEmpty();
        assertThat(outcome.fields().annTypes()).isEmpty();
    }

    @Test
    @DisplayName("给定非JSON对象输出（数组/纯文本），when单条抽取，then视为解析失败")
    void givenNonObjectRoot_whenExtractOne_thenParseFailed() {
        assertThat(extractor.extractOne(portReturning("[1,2,3]", "[1,2,3]"), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);
        assertThat(extractor.extractOne(portReturning("\"一段话\"", "\"一段话\""), "标题", "正文", List.of()).status())
                .isEqualTo(AnnouncementExtractor.OutcomeStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("给定提示词接线，when单条抽取，then系统提示词为常量且用户提示词含标题与PDF文本")
    void givenPromptWiring_whenExtractOne_thenSystemConstantAndUserContainsTitleAndPdfText() {
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();
        IntelligenceChatPort recording = (s, u) -> {
            system.set(s);
            user.set(u);
            return Optional.of(new ChatOutcome(FULL_JSON, 100));
        };

        extractor.extractOne(recording, "贵州茅台2026年半年度报告", "营业收入128.56亿元", List.of());

        assertThat(system.get()).isEqualTo(AnnouncementExtractPrompt.SYSTEM_PROMPT);
        assertThat(user.get()).contains("贵州茅台2026年半年度报告").contains("营业收入128.56亿元");
    }

    @Test
    @DisplayName("给定超长PDF文本，when单条抽取，then用户提示词截断至前8000字符并附截断标记")
    void givenOverlongPdfText_whenExtractOne_thenUserPromptTruncatedTo8000Chars() {
        AtomicReference<String> user = new AtomicReference<>();
        IntelligenceChatPort recording = (s, u) -> {
            user.set(u);
            return Optional.of(new ChatOutcome(FULL_JSON, 100));
        };
        String longText = "甲".repeat(9000);

        extractor.extractOne(recording, "标题", longText, List.of());

        assertThat(user.get()).contains("已截断");
        assertThat(user.get()).doesNotContain("甲".repeat(8001));
    }

    @Test
    @DisplayName("给定空白PDF文本（无pdf_url降级），when单条抽取，then用户提示词标注仅凭标题判断")
    void givenBlankPdfText_whenExtractOne_thenUserPromptMarksTitleOnly() {
        AtomicReference<String> user = new AtomicReference<>();
        IntelligenceChatPort recording = (s, u) -> {
            user.set(u);
            return Optional.of(new ChatOutcome(FULL_JSON, 100));
        };

        extractor.extractOne(recording, "标题足够长的公告", "", List.of());

        assertThat(user.get()).contains("标题足够长的公告").contains("无PDF正文");
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 按序返回给定文本（每次 100 inputTokens）；序号越界后返回 empty。 */
    private static IntelligenceChatPort portReturning(String... texts) {
        List<String> remaining = new java.util.ArrayList<>(List.of(texts));
        return (s, u) -> remaining.isEmpty() ? Optional.empty()
                : Optional.of(new ChatOutcome(remaining.removeFirst(), 100));
    }

    /** 首次返回给定文本，其后一律 empty（模拟 LLM 中途失效）。 */
    private static IntelligenceChatPort portReturningThenEmpty(String text) {
        boolean[] first = {true};
        return (s, u) -> {
            if (first[0]) {
                first[0] = false;
                return Optional.of(new ChatOutcome(text, 100));
            }
            return Optional.empty();
        };
    }
}
