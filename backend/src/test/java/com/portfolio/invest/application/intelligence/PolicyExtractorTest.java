package com.portfolio.invest.application.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.intelligence.IntelligenceChatPort.ChatOutcome;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyEvent;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 单条政策抽取纯组件（Task 8 eval runner 直调口径）：合法 JSON 六字段映射、isPolicy=false
 * 兜底转换（F12 误收录低置信标注——落库而非丢弃）、direction/strength/confidence 三受控枚举
 * 非法值判失败（null 容忍）、affectedAreas 数组宽严口径、围栏容错、空串兜底、解析失败重试
 * 1 次、LLM 通道不可用与解析失败可区分、两侧 inputTokens 累计（D16 护栏输入）。
 */
class PolicyExtractorTest {

    private static final String FULL_JSON = """
            {"isPolicy":true,"direction":"EASING","strength":"HIGH",
            "affectedAreas":["利率","房地产"],"summary":"央行降准0.5个百分点","confidence":"HIGH"}""";
    private static final String INVALID_JSON = "这不是 JSON{{{";
    private static final String NON_POLICY_JSON = """
            {"isPolicy":false,"direction":"NEUTRAL","strength":"LOW",
            "affectedAreas":[],"summary":"行长出席论坛并发表讲话","confidence":"LOW"}""";

    private final PolicyExtractor extractor = new PolicyExtractor();

    @Test
    @DisplayName("给定合法完整JSON，when单条抽取，then六字段正确映射且累计inputTokens")
    void givenFullValidJson_whenExtractOne_thenAllFieldsMapped() {
        var outcome = extractor.extractOne(portReturning(FULL_JSON), "央行决定降准", "为支持实体经济发展……");

        assertThat(outcome.status()).isEqualTo(PolicyExtractor.OutcomeStatus.SUCCESS);
        var fields = outcome.fields();
        assertThat(fields.isPolicy()).isTrue();
        assertThat(fields.direction()).isEqualTo(PolicyDirection.EASING);
        assertThat(fields.strength()).isEqualTo(PolicyStrength.HIGH);
        assertThat(fields.affectedAreas()).containsExactly("利率", "房地产");
        assertThat(fields.summary()).isEqualTo("央行降准0.5个百分点");
        assertThat(fields.confidence()).isEqualTo(PolicyConfidence.HIGH);
        assertThat(outcome.inputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("给定提示词接线，when单条抽取，then系统提示词为常量且用户提示词含标题与正文")
    void givenPromptWiring_whenExtractOne_thenSystemConstantAndUserContainsTitleContent() {
        AtomicReference<String> system = new AtomicReference<>();
        AtomicReference<String> user = new AtomicReference<>();
        IntelligenceChatPort recording = (s, u) -> {
            system.set(s);
            user.set(u);
            return Optional.of(new ChatOutcome(FULL_JSON, 100));
        };

        extractor.extractOne(recording, "央行决定降准", "为支持实体经济发展，降低金融机构存款准备金率0.5个百分点。");

        assertThat(system.get()).isEqualTo(PolicyExtractPrompt.SYSTEM_PROMPT);
        assertThat(user.get()).contains("央行决定降准").contains("降低金融机构存款准备金率");
    }

    @Test
    @DisplayName("给定超长正文，when单条抽取，then用户提示词截断至6000字符并附截断标记")
    void givenOverlongContent_whenExtractOne_thenUserPromptTruncated() {
        AtomicReference<String> user = new AtomicReference<>();
        IntelligenceChatPort recording = (s, u) -> {
            user.set(u);
            return Optional.of(new ChatOutcome(FULL_JSON, 100));
        };
        String longContent = "政".repeat(PolicyExtractPrompt.MAX_CONTENT_CHARS + 500);

        extractor.extractOne(recording, "央行决定降准", longContent);

        // 截断标记 + 恰好 MAX_CONTENT_CHARS 字符正文（标题/标签行不计入正文上限）
        assertThat(user.get()).contains("已截断至前 " + PolicyExtractPrompt.MAX_CONTENT_CHARS + " 字符");
        assertThat(user.get().length()).isLessThan(longContent.length());
    }

    @Test
    @DisplayName("给定模型违规加围栏，when单条抽取，then剥离围栏后仍解析成功")
    void givenFencedJson_whenExtractOne_thenFencesStrippedAndParsed() {
        String fenced = "```json\n" + FULL_JSON + "\n```";

        var outcome = extractor.extractOne(portReturning(fenced), "标题", "正文内容足够长");

        assertThat(outcome.status()).isEqualTo(PolicyExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.fields().summary()).isEqualTo("央行降准0.5个百分点");
    }

    @Test
    @DisplayName("给定空串或空白输出（全非文本块边界），when单条抽取，then视为解析失败")
    void givenBlankText_whenExtractOne_thenParseFailed() {
        assertThat(extractor.extractOne(portReturning("", ""), "标题", "正文").status())
                .isEqualTo(PolicyExtractor.OutcomeStatus.PARSE_FAILED);
        assertThat(extractor.extractOne(portReturning("   \n\t ", "   \n\t "), "标题", "正文").status())
                .isEqualTo(PolicyExtractor.OutcomeStatus.PARSE_FAILED);
    }

    @Test
    @DisplayName("给定首抽非法JSON重试合法，when单条抽取，then重试救回且两侧tokens累计")
    void givenInvalidThenValid_whenExtractOne_thenRescuedWithSummedTokens() {
        var outcome = extractor.extractOne(portReturning(INVALID_JSON, FULL_JSON), "标题", "正文");

        assertThat(outcome.status()).isEqualTo(PolicyExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.inputTokens()).isEqualTo(200); // 首抽 + 重试均计费
    }

    @Test
    @DisplayName("给定两次非法JSON，when单条抽取，then仅重试1次后标解析失败")
    void givenAlwaysInvalid_whenExtractOne_thenParseFailedAfterSingleRetry() {
        var outcome = extractor.extractOne(portReturning(INVALID_JSON, INVALID_JSON, FULL_JSON), "标题", "正文");

        assertThat(outcome.status()).isEqualTo(PolicyExtractor.OutcomeStatus.PARSE_FAILED);
        assertThat(outcome.fields()).isNull();
        assertThat(outcome.inputTokens()).isEqualTo(200); // 第 3 次调用不应发生
    }

    @Test
    @DisplayName("给定LLM未配置，when单条抽取，then返回通道不可用（区别于解析失败）")
    void givenLlmUnavailable_whenExtractOne_thenLlmUnavailableOutcome() {
        var outcome = extractor.extractOne((s, u) -> Optional.empty(), "标题", "正文");

        assertThat(outcome.status()).isEqualTo(PolicyExtractor.OutcomeStatus.LLM_UNAVAILABLE);
        assertThat(outcome.inputTokens()).isZero();
    }

    @Test
    @DisplayName("给定首抽解析失败重试遇LLM失效，when单条抽取，then按通道不可用处理且计首抽tokens")
    void givenInvalidThenLlmDown_whenExtractOne_thenLlmUnavailableWithFirstAttemptTokens() {
        var outcome = extractor.extractOne(portReturningThenEmpty(INVALID_JSON), "标题", "正文");

        assertThat(outcome.status()).isEqualTo(PolicyExtractor.OutcomeStatus.LLM_UNAVAILABLE);
        assertThat(outcome.inputTokens()).isEqualTo(100);
    }

    @Test
    @DisplayName("给定direction/strength/confidence越出受控枚举，when单条抽取，then视为解析失败")
    void givenEnumsOutOfControl_whenExtractOne_thenParseFailed() {
        assertParseFailedWithReplacement("\"direction\":\"EASING\"", "\"direction\":\"LOOSE\"");
        assertParseFailedWithReplacement("\"strength\":\"HIGH\"", "\"strength\":\"EXTREME\"");
        // confidence 受控仅 HIGH/LOW——MEDIUM 是 strength 的枚举名，越界即违约
        assertParseFailedWithReplacement("\"confidence\":\"HIGH\"", "\"confidence\":\"MEDIUM\"");
    }

    @Test
    @DisplayName("给定三枚举为null或小写枚举名，when单条抽取，thennull容忍与小写归一均接受")
    void givenEnumsNullOrLowercase_whenExtractOne_thenAccepted() {
        String nullEnums = """
                {"isPolicy":true,"direction":null,"strength":null,
                "affectedAreas":[],"summary":"方向不明的中性政策","confidence":null}""";
        var nullOutcome = extractor.extractOne(portReturning(nullEnums), "标题", "正文");
        assertThat(nullOutcome.status()).isEqualTo(PolicyExtractor.OutcomeStatus.SUCCESS);
        assertThat(nullOutcome.fields().direction()).isNull();
        assertThat(nullOutcome.fields().strength()).isNull();
        assertThat(nullOutcome.fields().confidence()).isNull();

        String lowercase = FULL_JSON.replace("EASING", "easing");
        assertThat(extractor.extractOne(portReturning(lowercase), "标题", "正文")
                .fields().direction()).isEqualTo(PolicyDirection.EASING);
    }

    @Test
    @DisplayName("给定isPolicy=false（领导活动漏网），when单条抽取，then兜底转换为哨兵字段（落库而非丢弃）")
    void givenNonPolicyLeakage_whenExtractOne_thenConvertedToFallbackFields() {
        var outcome = extractor.extractOne(portReturning(NON_POLICY_JSON), "行长出席论坛", "行长出席某论坛并发表讲话……");

        assertThat(outcome.status()).isEqualTo(PolicyExtractor.OutcomeStatus.SUCCESS);
        var fields = outcome.fields();
        assertThat(fields.isPolicy()).isFalse();
        assertThat(fields.direction()).isEqualTo(PolicyDirection.NEUTRAL);
        assertThat(fields.strength()).isEqualTo(PolicyStrength.LOW);
        assertThat(fields.summary()).isEqualTo(PolicyEvent.NON_POLICY_SUMMARY); // 「非政策类动态（过滤兜底）」
        assertThat(fields.confidence()).isEqualTo(PolicyConfidence.LOW);
        assertThat(fields.affectedAreas()).isEmpty();
    }

    @Test
    @DisplayName("给定isPolicy缺失或非布尔，when单条抽取，then视为解析失败")
    void givenIsPolicyMissingOrNonBoolean_whenExtractOne_thenParseFailed() {
        String missing = FULL_JSON.replace("\"isPolicy\":true,", "");
        assertParseFailed(missing);

        String nonBoolean = FULL_JSON.replace("true", "\"是\"");
        assertParseFailed(nonBoolean);
    }

    @Test
    @DisplayName("给定summary缺失或空白，when单条抽取，then视为解析失败")
    void givenMissingOrBlankSummary_whenExtractOne_thenParseFailed() {
        String missing = FULL_JSON.replace("\"summary\":\"央行降准0.5个百分点\",", "");
        assertParseFailed(missing);

        String blank = FULL_JSON.replace("央行降准0.5个百分点", "  ");
        assertParseFailed(blank);
    }

    @Test
    @DisplayName("给定affectedAreas缺失或null，when单条抽取，then归一为空列表；非数组判失败")
    void givenAffectedAreasEdgeCases_whenExtractOne_thenNormalizedOrFail() {
        String missing = "{\"isPolicy\":true,\"direction\":\"NEUTRAL\",\"strength\":\"LOW\",\"summary\":\"无领域政策\"}";
        var outcome = extractor.extractOne(portReturning(missing), "标题", "正文");
        assertThat(outcome.status()).isEqualTo(PolicyExtractor.OutcomeStatus.SUCCESS);
        assertThat(outcome.fields().affectedAreas()).isEmpty();

        String nonArray = FULL_JSON.replace("[\"利率\",\"房地产\"]", "\"利率、房地产\"");
        assertParseFailed(nonArray);
    }

    @Test
    @DisplayName("给定非JSON对象输出（数组/纯文本），when单条抽取，then视为解析失败")
    void givenNonObjectRoot_whenExtractOne_thenParseFailed() {
        assertParseFailed("[1,2,3]");
        assertParseFailed("\"一段话\"");
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    private void assertParseFailed(String json) {
        assertThat(extractor.extractOne(portReturning(json, json), "标题", "正文").status())
                .isEqualTo(PolicyExtractor.OutcomeStatus.PARSE_FAILED);
    }

    private void assertParseFailedWithReplacement(String target, String replacement) {
        assertParseFailed(FULL_JSON.replace(target, replacement));
    }

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
