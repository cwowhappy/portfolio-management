package com.portfolio.invest.application.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyEvent;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 单条政策 LLM 结构化抽取纯组件（F11/F12，照 {@link NewsExtractor} 切分模式）：输入标题 +
 * 政策正文（截 6000 字符在提示词层），一次 LLM 调用 + Jackson 解析，解析失败重试 1 次
 * （{@link #MAX_ATTEMPTS}，temperature 0 由端口侧钉死）。无状态非 Spring bean，服务装配与
 * Task 8 eval runner 直调共用同一行为。
 *
 * <p>解析契约（宽严口径）：根必须为 JSON 对象；summary 必须为非空文本、isPolicy 必须为
 * 布尔（两个产出锚点）；direction/strength/confidence 缺失/null 归 null，非受控枚举取值
 * 视为解析失败；affectedAreas 缺失/null 归空列表、非数组视为失败；模型违规加的 Markdown
 * 围栏剥离后再解析。Task 4 已知边界：端口可能返回 present("")（全非文本块）——空白一律按
 * 解析失败处理。
 *
 * <p><b>isPolicy=false 兜底转换（F12）</b>：LLM 判定非政策类（领导活动/会议新闻/转载/
 * 行政事务漏网）时，抽取结果整体置换为哨兵字段（direction=NEUTRAL、strength=LOW、
 * summary={@link PolicyEvent#NON_POLICY_SUMMARY}、confidence=LOW、affectedAreas 空）——
 * <b>落库而非丢弃</b>，由低置信标注链路（F12「误收录须标注为低置信」）可见可过滤。
 */
public class PolicyExtractor {

    /** 首抽 + 解析失败重试 1 次（与新闻/公告抽取同款）。 */
    static final int MAX_ATTEMPTS = 2;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 解析成功字段集（affectedAreas null 归一为空列表；isPolicy=false 时为哨兵置换字段）。 */
    public record ExtractedFields(PolicyDirection direction, PolicyStrength strength,
                                  List<String> affectedAreas, String summary,
                                  PolicyConfidence confidence, boolean isPolicy) {
        public ExtractedFields {
            affectedAreas = affectedAreas == null ? List.of() : List.copyOf(affectedAreas);
        }
    }

    /** 单条抽取三态：成功 / LLM 通道不可用（调用方整批跳过留 PENDING）/ 解析失败（调用方标 FAILED）。 */
    public enum OutcomeStatus { SUCCESS, LLM_UNAVAILABLE, PARSE_FAILED }

    /**
     * @param inputTokens 各次实际完成调用（含失败重试）的 inputTokens 累计——D16 护栏输入；
     *                    通道不可用结局时含失效前已完成的调用
     */
    public record ExtractionOutcome(OutcomeStatus status, ExtractedFields fields, long inputTokens) {
    }

    /**
     * 抽取一条政策。LLM 通道不可用（端口 empty）立即以 {@link OutcomeStatus#LLM_UNAVAILABLE}
     * 返回——区别于解析失败，调用方据此跳过整批而非标 FAILED（避免误杀留 PENDING 下批再试）。
     * isPolicy=false 的解析成功结果置换为兜底哨兵字段后返回（落库而非丢弃）。
     */
    public ExtractionOutcome extractOne(IntelligenceChatPort chatPort, String title, String contentText) {
        String userPrompt = PolicyExtractPrompt.userPrompt(title, contentText);
        long tokens = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<IntelligenceChatPort.ChatOutcome> outcome =
                    chatPort.complete(PolicyExtractPrompt.SYSTEM_PROMPT, userPrompt);
            if (outcome.isEmpty()) {
                return new ExtractionOutcome(OutcomeStatus.LLM_UNAVAILABLE, null, tokens);
            }
            tokens += outcome.get().inputTokens();
            ExtractedFields fields = parse(outcome.get().text());
            if (fields != null) {
                return new ExtractionOutcome(OutcomeStatus.SUCCESS, fallbackIfNonPolicy(fields), tokens);
            }
        }
        return new ExtractionOutcome(OutcomeStatus.PARSE_FAILED, null, tokens);
    }

    /** 解析 LLM 输出文本；不合契约返回 null（调用方按解析失败处理）。 */
    private ExtractedFields parse(String text) {
        if (text == null || text.isBlank()) {
            return null; // Task 4 边界：present("") 空串兜底
        }
        JsonNode root;
        try {
            root = mapper.readTree(stripFences(text));
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.isObject()) {
            return null;
        }
        JsonNode isPolicyNode = root.get("isPolicy");
        if (isPolicyNode == null || !isPolicyNode.isBoolean()) {
            return null; // isPolicy 是产出锚点（兜底转换与 F12 标注依赖）：缺失/非布尔即失败
        }
        String summary = textOrNull(root.get("summary"));
        if (summary == null || summary.isBlank()) {
            return null;
        }
        PolicyDirection direction = parseEnum(root.get("direction"), PolicyDirection.class);
        PolicyStrength strength = parseEnum(root.get("strength"), PolicyStrength.class);
        PolicyConfidence confidence = parseEnum(root.get("confidence"), PolicyConfidence.class);
        if (presentButIllegal(root.get("direction"), direction)
                || presentButIllegal(root.get("strength"), strength)
                || presentButIllegal(root.get("confidence"), confidence)) {
            return null; // 受控枚举违约（非 null 占位但取值不合法）
        }
        List<String> affectedAreas = stringList(root.get("affectedAreas"));
        if (affectedAreas == null) {
            return null; // 数组字段类型违约（缺失/null 已归一为空列表，此处是非数组取值）
        }
        return new ExtractedFields(direction, strength, affectedAreas, summary, confidence,
                isPolicyNode.asBoolean());
    }

    /** isPolicy=false 兜底置换：哨兵字段全量覆盖（落库而非丢弃，F12 低置信标注）。 */
    private static ExtractedFields fallbackIfNonPolicy(ExtractedFields fields) {
        if (fields.isPolicy()) {
            return fields;
        }
        return new ExtractedFields(PolicyDirection.NEUTRAL, PolicyStrength.LOW, List.of(),
                PolicyEvent.NON_POLICY_SUMMARY, PolicyConfidence.LOW, false);
    }

    /** 枚举字段：缺失/null → null；文本取值大小写不敏感归一 → 枚举；非文本 → null（调用方按违约判）。 */
    private static <E extends Enum<E>> E parseEnum(JsonNode node, Class<E> type) {
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return Enum.valueOf(type, node.asText().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 受控枚举违约判定：字段占位非 null（文本形态）但解析不出合法枚举名。 */
    private static boolean presentButIllegal(JsonNode node, Enum<?> parsed) {
        return node != null && !node.isNull() && node.isTextual() && parsed == null;
    }

    /** 文本字段：缺失/null → null；其余按文本取值（数值等宽容转字符串）。 */
    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 数组字段：缺失/null → 空列表；数组 → 非空字符串元素列表；非数组 → null（违约）。 */
    private static List<String> stringList(JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            return null;
        }
        List<String> out = new ArrayList<>();
        for (JsonNode element : node) {
            String text = element.asText();
            if (text != null && !text.isBlank()) {
                out.add(text.trim());
            }
        }
        return List.copyOf(out);
    }

    /** 剥离模型违规加的 Markdown 围栏（```json … ```）；无围栏原样返回（trim 后）。 */
    private static String stripFences(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int lineBreak = trimmed.indexOf('\n');
        if (lineBreak < 0) {
            return trimmed;
        }
        String body = trimmed.substring(lineBreak + 1).trim();
        if (body.endsWith("```")) {
            body = body.substring(0, body.length() - 3);
        }
        return body.trim();
    }
}
