package com.portfolio.invest.application.intelligence;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * LLM 抽取输出 JSON 的字段级解析共享助手（2026-10-12 审查 B2）：新闻/政策/公告三个
 * 抽取器誊写的解析骨架单实现——Markdown 围栏剥离、文本字段、字符串数组字段、受控
 * 枚举字段。宽严口径三域一致（缺失/null 归一、类型违约判解析失败），防逐处复制漂移。
 * 公告的数值字段/违约异常与「枚举名剔除不判失败」口径为域特有，留在
 * {@link AnnouncementExtractor} 本地。
 */
final class LlmJsonParseSupport {

    private LlmJsonParseSupport() {
    }

    /** 文本字段：缺失/null → null；其余按文本取值（数值等宽容转字符串）。 */
    static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asText();
    }

    /** 字符串数组字段：缺失/null → 空列表；数组 → 非空字符串元素列表；非数组 → null（违约）。 */
    static List<String> stringList(JsonNode node) {
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

    /** 枚举字段：缺失/null/非文本 → null；文本取值大小写不敏感归一 → 枚举；非受控名 → null（调用方按违约判）。 */
    static <E extends Enum<E>> E parseEnum(JsonNode node, Class<E> type) {
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
    static boolean presentButIllegal(JsonNode node, Enum<?> parsed) {
        return node != null && !node.isNull() && node.isTextual() && parsed == null;
    }

    /** 剥离模型违规加的 Markdown 围栏（```json … ```）；无围栏原样返回（trim 后）。 */
    static String stripFences(String text) {
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
