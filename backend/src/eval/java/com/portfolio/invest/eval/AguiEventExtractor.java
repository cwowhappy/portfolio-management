package com.portfolio.invest.eval;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SSE 事件流观察抽取：工具调用序列（TOOL_CALL_START + TOOL_CALL_ARGS delta 累积，带轮序）、
 * assistant 正文（TEXT_MESSAGE_START 的 role 过滤 + CONTENT delta 拼接）、图表事件
 * （TOOL_CALL_RESULT 含 specVersion 标记，ChartSpec 判别联合的固定组件）、token 用量
 * （CUSTOM token_usage 累计值）、HITL 中断（RUN_FINISHED outcome=interrupt 的条目）。
 */
public final class AguiEventExtractor {

    private AguiEventExtractor() {}

    /** 单次工具调用观察：名字 + 全量入参文本（args delta 拼接）+ 结果文本（judge 数值核对的事实源）。 */
    public record ToolObservation(int turnIndex, String toolCallId, String toolName, String argsText,
                                  String resultText) {

        public String signature() {
            return toolName + "(" + argsText + ")";
        }

        /** judge 提示词用：调用与返回摘要（返回截断，图表 spec 等大段不全文透传）。 */
        public String forJudge(int maxResultChars) {
            String result = resultText == null ? "" : resultText;
            String truncated = result.length() <= maxResultChars ? result : result.substring(0, maxResultChars) + "…";
            return signature() + " => " + truncated;
        }
    }

    /**
     * RUN_FINISHED outcome=interrupt 的单条中断观察（HITL 维度断言口径同 McpHitlIntegrationTest：
     * kind 取 metadata."agentscope.interruptKind"，工具名取 metadata.toolName）。
     */
    public record InterruptObservation(int turnIndex, String interruptId, String toolCallId,
                                       String toolName, String kind, String reason) {}

    /** token 用量（取流内最后一个 cumulative 快照，即整 run 累计）。 */
    public record TokenUsage(Long inputTokens, Long outputTokens, Long totalTokens, int snapshots) {}

    /** 全部事件（跨轮）聚合出的观察结果。 */
    public record Transcript(
            List<ToolObservation> toolCalls,
            String assistantText,
            int chartEventCount,
            TokenUsage tokenUsage,
            List<String> runErrors,
            List<InterruptObservation> interrupts) {

        public List<String> toolNames() {
            return toolCalls.stream().map(ToolObservation::toolName).toList();
        }
    }

    public static Transcript extract(List<AguiDriver.SseTurn> turns) {
        Map<String, String> toolNamesById = new LinkedHashMap<>();
        Map<String, Integer> turnIndexById = new LinkedHashMap<>();
        Map<String, StringBuilder> argsById = new LinkedHashMap<>();
        Map<String, StringBuilder> resultsById = new LinkedHashMap<>();
        List<String> orderedToolCallIds = new ArrayList<>();
        Map<String, String> messageRoles = new LinkedHashMap<>();
        StringBuilder assistantText = new StringBuilder();
        int chartEvents = 0;
        TokenUsage usage = new TokenUsage(null, null, null, 0);
        List<String> runErrors = new ArrayList<>();
        List<InterruptObservation> interrupts = new ArrayList<>();

        for (AguiDriver.SseTurn turn : turns) {
            for (JsonNode event : turn.events()) {
                String type = event.path("type").asText("");
                switch (type) {
                    case "TOOL_CALL_START" -> {
                        String id = event.path("toolCallId").asText();
                        toolNamesById.put(id, event.path("toolCallName").asText());
                        turnIndexById.putIfAbsent(id, turn.index());
                        argsById.putIfAbsent(id, new StringBuilder());
                        orderedToolCallIds.add(id);
                    }
                    case "TOOL_CALL_ARGS" -> {
                        String id = event.path("toolCallId").asText();
                        StringBuilder args = argsById.get(id);
                        if (args != null) args.append(event.path("delta").asText());
                    }
                    case "TEXT_MESSAGE_START" ->
                            messageRoles.put(event.path("messageId").asText(), event.path("role").asText());
                    case "TEXT_MESSAGE_CONTENT" -> {
                        // 只拼 assistant 正文（role 缺失时按 assistant 处理：AG-UI 正文消息仅助手发）
                        String role = messageRoles.get(event.path("messageId").asText());
                        if (role == null || "assistant".equals(role)) {
                            assistantText.append(event.path("delta").asText());
                        }
                    }
                    case "TOOL_CALL_RESULT" -> {
                        String content = event.path("content").asText("");
                        if (content.contains("\"specVersion\"")) chartEvents++;
                        resultsById.computeIfAbsent(event.path("toolCallId").asText(),
                                k -> new StringBuilder()).append(content);
                    }
                    case "CUSTOM" -> {
                        if ("token_usage".equals(event.path("name").asText())) {
                            JsonNode cumulative = event.path("value").path("cumulative");
                            if (!cumulative.isMissingNode()) {
                                usage = new TokenUsage(
                                        cumulative.path("inputTokens").isNumber()
                                                ? cumulative.path("inputTokens").asLong() : null,
                                        cumulative.path("outputTokens").isNumber()
                                                ? cumulative.path("outputTokens").asLong() : null,
                                        cumulative.path("totalTokens").isNumber()
                                                ? cumulative.path("totalTokens").asLong() : null,
                                        usage.snapshots() + 1);
                            }
                        }
                    }
                    case "RUN_FINISHED" -> {
                        // HITL 权限中断：outcome.type=interrupt，条目口径同 McpHitlIntegrationTest
                        JsonNode outcome = event.path("outcome");
                        if ("interrupt".equals(outcome.path("type").asText(""))) {
                            for (JsonNode interrupt : outcome.path("interrupts")) {
                                JsonNode metadata = interrupt.path("metadata");
                                interrupts.add(new InterruptObservation(turn.index(),
                                        interrupt.path("id").asText(null),
                                        interrupt.path("toolCallId").asText(null),
                                        metadata.path("toolName").asText(null),
                                        metadata.path("agentscope.interruptKind").asText(null),
                                        interrupt.path("reason").asText(null)));
                            }
                        }
                    }
                    case "RUN_ERROR" -> runErrors.add(event.path("message").asText(""));
                    default -> { /* 其余事件类型不参与断言 */ }
                }
            }
        }
        List<ToolObservation> calls = orderedToolCallIds.stream()
                .map(id -> new ToolObservation(turnIndexById.getOrDefault(id, 0), id, toolNamesById.get(id),
                        argsById.getOrDefault(id, new StringBuilder()).toString(),
                        resultsById.getOrDefault(id, new StringBuilder()).toString()))
                .toList();
        return new Transcript(calls, assistantText.toString(), chartEvents, usage, runErrors, interrupts);
    }
}
