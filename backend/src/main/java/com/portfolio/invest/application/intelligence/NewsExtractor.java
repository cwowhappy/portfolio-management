package com.portfolio.invest.application.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.Direction;
import java.util.List;
import java.util.Optional;

/**
 * 单条新闻 LLM 结构化抽取纯组件（D6）：输入标题+摘要，一次 LLM 调用 + Jackson 解析，
 * 解析失败重试 1 次（{@link #MAX_ATTEMPTS}，temperature 0 由端口侧钉死）。无状态非 Spring bean
 * （同 PrincipleAlertEvaluator/CsvImportParser 惯例），服务装配与 Task 13 eval runner 直调共用同一行为。
 *
 * <p>解析契约（宽严口径）：根必须为 JSON 对象；summary 必须为非空文本、importance 必须为
 * 0..100 数值（落库前夹紧）；direction 缺失/null 归 null，非受控枚举值视为解析失败；
 * 数组字段缺失/null 归空列表、非数组视为失败；模型违规加的 Markdown 围栏剥离后再解析。
 * Task 4 已知边界：端口可能返回 present("")（全非文本块）——空白一律按解析失败处理。
 */
public class NewsExtractor {

    /** D6：首抽 + 解析失败重试 1 次。 */
    static final int MAX_ATTEMPTS = 2;

    private final ObjectMapper mapper = new ObjectMapper();

    /** 解析成功字段集（importance 已夹紧 0..100；列表字段 null 归一为空列表）。 */
    public record ExtractedFields(String eventType, List<String> stockCodes, List<String> industryCodes,
                                  String summary, Direction direction, List<String> keyNumbers,
                                  int importance) {
        public ExtractedFields {
            stockCodes = stockCodes == null ? List.of() : List.copyOf(stockCodes);
            industryCodes = industryCodes == null ? List.of() : List.copyOf(industryCodes);
            keyNumbers = keyNumbers == null ? List.of() : List.copyOf(keyNumbers);
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
     * 抽取一条新闻。LLM 通道不可用（端口 empty）立即以 {@link OutcomeStatus#LLM_UNAVAILABLE}
     * 返回——区别于解析失败，调用方据此跳过整批而非标 FAILED（避免误杀留 PENDING 下批再试）。
     */
    public ExtractionOutcome extractOne(IntelligenceChatPort chatPort, String title, String summary) {
        String userPrompt = NewsExtractPrompt.userPrompt(title, summary);
        long tokens = 0;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Optional<IntelligenceChatPort.ChatOutcome> outcome =
                    chatPort.complete(NewsExtractPrompt.SYSTEM_PROMPT, userPrompt);
            if (outcome.isEmpty()) {
                return new ExtractionOutcome(OutcomeStatus.LLM_UNAVAILABLE, null, tokens);
            }
            tokens += outcome.get().inputTokens();
            ExtractedFields fields = parse(outcome.get().text());
            if (fields != null) {
                return new ExtractionOutcome(OutcomeStatus.SUCCESS, fields, tokens);
            }
        }
        return new ExtractionOutcome(OutcomeStatus.PARSE_FAILED, null, tokens);
    }

    /** 解析 LLM 输出文本；不合契约返回 null（调用方按解析失败处理）。 */
    private ExtractedFields parse(String text) {
        JsonNode root = LlmJsonParseSupport.readObject(mapper, text);
        if (root == null) {
            return null;
        }
        String summary = LlmJsonParseSupport.textOrNull(root.get("summary"));
        if (summary == null || summary.isBlank()) {
            return null;
        }
        JsonNode importanceNode = root.get("importance");
        if (importanceNode == null || importanceNode.isNull() || !importanceNode.isNumber()) {
            return null;
        }
        Direction direction = LlmJsonParseSupport.parseEnum(root.get("direction"), Direction.class);
        if (LlmJsonParseSupport.presentButIllegal(root.get("direction"), direction)) {
            return null; // 受控枚举违约（非 null 占位但取值不合法）
        }
        List<String> stockCodes = LlmJsonParseSupport.stringList(root.get("stock_codes"));
        List<String> industryCodes = LlmJsonParseSupport.stringList(root.get("industry_codes"));
        List<String> keyNumbers = LlmJsonParseSupport.stringList(root.get("key_numbers"));
        if (stockCodes == null || industryCodes == null || keyNumbers == null) {
            return null; // 数组字段类型违约（缺失/null 已归一为空列表，此处是非数组取值）
        }
        return new ExtractedFields(LlmJsonParseSupport.textOrNull(root.get("event_type")), stockCodes, industryCodes,
                summary, direction, keyNumbers,
                Math.max(0, Math.min(100, importanceNode.asInt())));
    }
}
