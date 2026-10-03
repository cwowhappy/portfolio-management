package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.intelligence.IntelligenceChatPort.ChatOutcome;
import com.portfolio.invest.domain.intelligence.Direction;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 单条新闻抽取纯组件（Task 13 eval runner 直调口径）：合法 JSON 全字段映射、围栏容错、
 * 空串/空白兜底（Task 4 已知边界：全非文本块时 present("")）、解析失败重试 1 次（D6）、
 * LLM 通道不可用与解析失败可区分、direction 受控枚举校验、importance 夹紧 0..100、
 * 两侧 inputTokens 累计（D16 护栏输入）。
 */
class NewsExtractorTest {

    private static final String FULL_JSON = """
            {"event_type":"EARNINGS","stock_codes":["600519","000858"],"industry_codes":["801250"],
            "summary":"业绩超预期","direction":"BULLISH","key_numbers":["营收 12.34 亿元","净利同比 +25.3%"],"importance":85}""";
    private static final String INVALID_JSON = "这不是 JSON{{{";

    private final NewsExtractor extractor = new NewsExtractor();

    @Test
    @DisplayName("给定合法完整JSON，when单条抽取，then全字段正确映射且累计inputTokens")
    void givenFullValidJson_whenExtractOne_thenAllFieldsMapped() {
        var outcome = extractor.extractOne(portReturning(FULL_JSON), "茅台三季报", "营收净利双增");

        assertThat(outcome.status()).isEqualTo(NewsExtractor.OutcomeStatus.SUCCESS);
        var fields = outcome.fields();
        assertThat(fields.eventType()).isEqualTo("EARNINGS");
        assertThat(fields.stockCodes()).containsExactly("600519", "000858");
        assertThat(fields.industryCodes()).containsExactly("801250");
        assertThat(fields.summary()).isEqualTo("业绩超预期");
        assertThat(fields.direction()).isEqualTo(Direction.BULLISH);
        assertThat(fields.keyNumbers()).containsExactly("营收 12.34 亿元", "净利同比 +25.3%");
        assertThat(fields.importance()).isEqualTo(85);
        assertThat(outcome.inputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("给定提示词接线，when单条抽取，then系统提示词为常量且用户提示词含标题与摘要")
    void givenPromptWiring_whenExtractOne_thenSystemConstantAndUserContainsTitleSummary() {
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();
        IntelligenceChatPort recording = (s, u) -> {
            system.set(s);
            user.set(u);
            return Optional.of(new ChatOutcome(FULL_JSON, 100));
        };

        extractor.extractOne(recording, "茅台三季报", "营收净利双增");

        assertThat(system.get()).isEqualTo(NewsExtractPrompt.SYSTEM_PROMPT);
        assertThat(user.get()).contains("茅台三季报").contains("营收净利双增");
    }

    @Test
    @DisplayName("给定模型违规加围栏，when单条抽取，then剥离围栏后仍解析成功")
    void givenFencedJson_whenExtractOne_thenFencesStrippedAndParsed() {
        String fenced = "```json\n" + FULL_JSON + "\n```";

        var outcome = extractor.extractOne(portReturning(fenced), "标题", "摘要");

        assertThat(outcome.status()).isEqualTo(NewsExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.fields().summary()).isEqualTo("业绩超预期");
    }

    @Test
    @DisplayName("给定空串或空白输出（全非文本块边界），when单条抽取，then视为解析失败")
    void givenBlankText_whenExtractOne_thenParseFailed() {
        assertThat(extractor.extractOne(portReturning("", ""), "标题", "摘要").status())
                .isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);
        assertThat(extractor.extractOne(portReturning("   \n\t ", "   \n\t "), "标题", "摘要").status())
                .isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("给定首抽非法JSON重试合法，when单条抽取，then重试救回且两侧tokens累计")
    void givenInvalidThenValid_whenExtractOne_thenRescuedWithSummedTokens() {
        var outcome = extractor.extractOne(portReturning(INVALID_JSON, FULL_JSON), "标题", "摘要");

        assertThat(outcome.status()).isEqualTo(NewsExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.inputTokens()).isEqualTo(200); // 首抽 + 重试均计费
    }

    @Test
    @DisplayName("给定两次非法JSON，when单条抽取，then仅重试1次后标解析失败")
    void givenAlwaysInvalid_whenExtractOne_thenParseFailedAfterSingleRetry() {
        var outcome = extractor.extractOne(portReturning(INVALID_JSON, INVALID_JSON, FULL_JSON), "标题", "摘要");

        assertThat(outcome.status()).isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);
        assertThat(outcome.fields()).isNull();
        assertThat(outcome.inputTokens()).isEqualTo(200); // 第 3 次调用不应发生
    }

    @Test
    @DisplayName("给定LLM未配置，when单条抽取，then返回通道不可用（区别于解析失败）")
    void givenLlmUnavailable_whenExtractOne_thenLlmUnavailableOutcome() {
        var outcome = extractor.extractOne((s, u) -> Optional.empty(), "标题", "摘要");

        assertThat(outcome.status()).isEqualTo(NewsExtractor.OutcomeStatus.LLM_UNAVAILABLE);
        assertThat(outcome.inputTokens()).isZero();
    }

    @Test
    @DisplayName("给定首抽解析失败重试遇LLM失效，when单条抽取，then按通道不可用处理且计首抽tokens")
    void givenInvalidThenLlmDown_whenExtractOne_thenLlmUnavailableWithFirstAttemptTokens() {
        var outcome = extractor.extractOne(portReturningThenEmpty(INVALID_JSON), "标题", "摘要");

        assertThat(outcome.status()).isEqualTo(NewsExtractor.OutcomeStatus.LLM_UNAVAILABLE);
        assertThat(outcome.inputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("给定direction越出受控枚举，when单条抽取，then视为解析失败")
    void givenDirectionOutOfEnum_whenExtractOne_thenParseFailed() {
        String json = FULL_JSON.replace("BULLISH", "UP");

        assertThat(extractor.extractOne(portReturning(json, json), "标题", "摘要").status())
                .isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("给定direction为null或小写枚举名，when单条抽取，thennull与小写归一均接受")
    void givenDirectionNullOrLowercase_whenExtractOne_thenAccepted() {
        String nullDir = FULL_JSON.replace("\"BULLISH\"", "null");
        assertThat(extractor.extractOne(portReturning(nullDir), "标题", "摘要").fields().direction()).isNull();

        String lowercase = FULL_JSON.replace("BULLISH", "bearish");
        assertThat(extractor.extractOne(portReturning(lowercase), "标题", "摘要").fields().direction())
                .isEqualTo(Direction.BEARISH);
    }

    @Test
    @DisplayName("给定importance越界或缺失或非数，when单条抽取，then夹紧0..100、缺失非数判失败")
    void givenImportanceEdgeCases_whenExtractOne_thenClampOrFail() {
        String above = FULL_JSON.replace("85", "150");
        assertThat(extractor.extractOne(portReturning(above), "标题", "摘要").fields().importance()).isEqualTo(100);

        String below = FULL_JSON.replace("85", "-5");
        assertThat(extractor.extractOne(portReturning(below), "标题", "摘要").fields().importance()).isZero();

        String missing = FULL_JSON.replace(",\"importance\":85", "");
        assertThat(extractor.extractOne(portReturning(missing, missing), "标题", "摘要").status())
                .isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);

        String notNumber = FULL_JSON.replace("85", "\"很高\"");
        assertThat(extractor.extractOne(portReturning(notNumber, notNumber), "标题", "摘要").status())
                .isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("给定summary缺失或空白，when单条抽取，then视为解析失败")
    void givenMissingOrBlankSummary_whenExtractOne_thenParseFailed() {
        String missing = FULL_JSON.replace("\"summary\":\"业绩超预期\",", "");
        assertThat(extractor.extractOne(portReturning(missing, missing), "标题", "摘要").status())
                .isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);

        String blank = FULL_JSON.replace("业绩超预期", "  ");
        assertThat(extractor.extractOne(portReturning(blank, blank), "标题", "摘要").status())
                .isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("给定数组字段缺失或null，when单条抽取，then归一为空列表")
    void givenMissingArrays_whenExtractOne_thenNormalizedToEmptyLists() {
        String json = "{\"event_type\":null,\"summary\":\"只有摘要\",\"direction\":null,\"importance\":50}";

        var outcome = extractor.extractOne(portReturning(json), "标题", "摘要");

        assertThat(outcome.status()).isEqualTo(NewsExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.fields().stockCodes()).isEmpty();
        assertThat(outcome.fields().industryCodes()).isEmpty();
        assertThat(outcome.fields().keyNumbers()).isEmpty();
        assertThat(outcome.fields().eventType()).isNull();
        assertThat(outcome.fields().direction()).isNull();
    }

    @Test
    @DisplayName("给定非JSON对象输出（数组/纯文本），when单条抽取，then视为解析失败")
    void givenNonObjectRoot_whenExtractOne_thenParseFailed() {
        assertThat(extractor.extractOne(portReturning("[1,2,3]", "[1,2,3]"), "标题", "摘要").status())
                .isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);
        assertThat(extractor.extractOne(portReturning("\"一段话\"", "\"一段话\""), "标题", "摘要").status())
                .isEqualTo(NewsExtractor.OutcomeStatus.PARSE_FAILED);
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
