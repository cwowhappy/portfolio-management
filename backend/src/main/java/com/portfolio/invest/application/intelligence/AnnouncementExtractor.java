package com.portfolio.invest.application.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 单条公告 LLM 结构化抽取纯组件（F07/决策 #11）：输入标题 + PDF 文本，一次 LLM 调用 +
 * Jackson 解析，解析失败重试 1 次（{@link #MAX_ATTEMPTS}，temperature 0 由端口侧钉死）。
 * 无状态非 Spring bean（照 {@link NewsExtractor} 切分模式），服务装配与 Task 9 eval
 * runner 直调共用同一行为。
 *
 * <p>解析契约（宽严口径）：根必须为 JSON 对象；metrics 必须为对象（核心产出锚点），五数值
 * 字段缺失/null 归 null、数字或数字串归 BigDecimal、其余取值（非数字文本/布尔/对象）视为
 * 解析失败；dividendDesc 缺失/null 归 null；undisclosed 缺失/null 归空列表、非数组视为
 * 失败；annTypes 缺失/null 归空列表、非数组视为失败，数组内**非受控枚举名直接剔除不判
 * 失败**（∩ 有效枚举名）。模型违规加的 Markdown 围栏剥离后再解析。Task 4 已知边界：
 * 端口可能返回 present("")（全非文本块）——空白一律按解析失败处理。
 *
 * <p>annTypes 并集语义：源站栏目直判类型（调用方按 ann_type_source 映射传入，直判在前）
 * ∪ LLM 精判类型，LinkedHashSet 保序去重。公告无 importance 字段（区别于新闻抽取）。
 */
public class AnnouncementExtractor {

    /** 首抽 + 解析失败重试 1 次（与新闻抽取同款）。 */
    static final int MAX_ATTEMPTS = 2;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 解析成功字段集（metrics 六字段 + annTypes 栏目直判 ∪ LLM 精判并集）。 */
    public record ExtractedFields(AnnouncementMetrics metrics, List<AnnouncementType> annTypes) {
        public ExtractedFields {
            annTypes = annTypes == null ? List.of() : List.copyOf(annTypes);
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
     * 抽取一条公告。LLM 通道不可用（端口 empty）立即以 {@link OutcomeStatus#LLM_UNAVAILABLE}
     * 返回——区别于解析失败，调用方据此跳过整批而非标 FAILED（避免误杀留 PENDING 下批再试）。
     *
     * @param sourceTypes 源站栏目映射直判的类型（采集侧 ann_type_source 关键词映射），并入
     *                    annTypes 并集且排在前；无直判传空列表
     */
    public ExtractionOutcome extractOne(IntelligenceChatPort chatPort, String title, String pdfText,
                                        List<AnnouncementType> sourceTypes) {
        String userPrompt = AnnouncementExtractPrompt.userPrompt(title, pdfText);
        long tokens = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<IntelligenceChatPort.ChatOutcome> outcome =
                    chatPort.complete(AnnouncementExtractPrompt.SYSTEM_PROMPT, userPrompt);
            if (outcome.isEmpty()) {
                return new ExtractionOutcome(OutcomeStatus.LLM_UNAVAILABLE, null, tokens);
            }
            tokens += outcome.get().inputTokens();
            ExtractedFields fields = parse(outcome.get().text(), sourceTypes);
            if (fields != null) {
                return new ExtractionOutcome(OutcomeStatus.SUCCESS, fields, tokens);
            }
        }
        return new ExtractionOutcome(OutcomeStatus.PARSE_FAILED, null, tokens);
    }

    /** 解析 LLM 输出文本；不合契约返回 null（调用方按解析失败处理）。 */
    private ExtractedFields parse(String text, List<AnnouncementType> sourceTypes) {
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
        try {
            JsonNode metricsNode = root.get("metrics");
            if (metricsNode == null || !metricsNode.isObject()) {
                return null; // metrics 是核心产出锚点：缺失/非对象即失败
            }
            AnnouncementMetrics metrics = new AnnouncementMetrics(
                    decimal(metricsNode.get("revenueYi")),
                    decimal(metricsNode.get("netProfitYi")),
                    decimal(metricsNode.get("netProfitYoyPct")),
                    decimal(metricsNode.get("deductedProfitYi")),
                    decimal(metricsNode.get("grossMarginPct")),
                    textOrNull(metricsNode.get("dividendDesc")),
                    stringList(metricsNode.get("undisclosed")));
            return new ExtractedFields(metrics, union(sourceTypes, enumList(root.get("annTypes"))));
        } catch (ContractViolationException e) {
            return null; // 字段类型违约（非数字数值/非数组列表）
        }
    }

    /** 内部契约违约信号（不外抛，parse 统一归解析失败）。 */
    private static final class ContractViolationException extends Exception {
    }

    /** 数值字段：缺失/null → null；数字或数字串 → BigDecimal；其余取值（非数字文本/布尔/对象）→ 违约。 */
    private static BigDecimal decimal(JsonNode node) throws ContractViolationException {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        if (node.isTextual()) {
            try {
                return new BigDecimal(node.asText().trim());
            } catch (NumberFormatException e) {
                throw new ContractViolationException();
            }
        }
        throw new ContractViolationException();
    }

    /** 文本字段：缺失/null → null；其余按文本取值（数值等宽容转字符串）。 */
    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 字符串数组字段：缺失/null → 空列表；数组 → 非空字符串元素列表；非数组 → 违约。 */
    private static List<String> stringList(JsonNode node) throws ContractViolationException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ContractViolationException();
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

    /** 枚举数组字段：缺失/null → 空列表；数组 → 受控枚举名（大小写不敏感）过滤，非受控名剔除不判失败；非数组 → 违约。 */
    private static List<AnnouncementType> enumList(JsonNode node) throws ContractViolationException {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new ContractViolationException();
        }
        List<AnnouncementType> out = new ArrayList<>();
        for (JsonNode element : node) {
            if (!element.isTextual()) {
                continue; // 非文本元素同非受控名处理：剔除
            }
            try {
                out.add(AnnouncementType.valueOf(element.asText().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                // 非受控枚举名：∩ 有效枚举名语义，剔除不判失败
            }
        }
        return List.copyOf(out);
    }

    /** 并集语义：源站直判在前 + LLM 精判增补，LinkedHashSet 保序去重。 */
    private static List<AnnouncementType> union(List<AnnouncementType> sourceTypes,
                                                List<AnnouncementType> llmTypes) {
        LinkedHashSet<AnnouncementType> merged =
                new LinkedHashSet<>(sourceTypes == null ? List.of() : sourceTypes);
        merged.addAll(llmTypes == null ? List.of() : llmTypes);
        return List.copyOf(merged);
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
