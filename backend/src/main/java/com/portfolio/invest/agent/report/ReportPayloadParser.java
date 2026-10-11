package com.portfolio.invest.agent.report;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 研报 payload 围栏提取与六态校验（MS-32 B6，设计规格 §3.4）：从 Agent 草稿轮最终消息
 * 提取 {@code ```research-report} 围栏 JSON 并反序列化为 {@link ResearchReportSpec.V1}，
 * 违规抛 {@link ReportPayloadException}（kind 供编排器分流回炉反馈/终态失败）。
 *
 * <p>围栏正则沿前端 {@code lib/research-draft.ts} 惰性匹配先例（后端侧同构 research-report
 * 围栏）：取首个围栏、围栏外正文噪音不参与；JSON 里 {@code }} 后紧跟 {@code ```} 的截断
 * 边界（contentMd 字符串含三反引号代码块）按 JSON_INVALID 走格式反馈重试，不特判。
 * 校验序：围栏 →（Ruling-21 修复②：字符串字面量裸控制字符预清洗）→ JSON → specVersion →
 * title → contentMd → 字节上限（UTF-8 字节口径，contentMd 独计，恰超 = 字节数 &gt; 上限，
 * 等于上限放行）。
 */
public final class ReportPayloadParser {

    private static final Pattern FENCE =
            Pattern.compile("```research-report\\s*(\\{.*?\\})\\s*```", Pattern.DOTALL);

    /** 解析专用 mapper（Jackson 2，沿 AguiWireJsonConfig 同栈；未知字段按 JSON_INVALID 反馈自纠）。 */
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReportPayloadParser() {}

    /** 提取并校验围栏 JSON；agentFinalText 为 null/无围栏按 FENCE_MISSING（编排器格式反馈重试）。 */
    public static ResearchReportSpec.V1 parse(String agentFinalText, int draftMaxBytes) {
        Matcher fence = FENCE.matcher(agentFinalText == null ? "" : agentFinalText);
        if (!fence.find()) {
            throw new ReportPayloadException(ReportPayloadException.Kind.FENCE_MISSING,
                    "最终消息未包含 ```research-report 围栏块（输出契约要求单个围栏 JSON）");
        }
        ResearchReportSpec.V1 spec;
        try {
            spec = MAPPER.readValue(escapeRawControlCharsInStrings(fence.group(1)),
                    ResearchReportSpec.V1.class);
        } catch (JsonProcessingException e) {
            throw new ReportPayloadException(ReportPayloadException.Kind.JSON_INVALID,
                    "围栏内不是合法的研报 JSON：" + e.getOriginalMessage());
        }
        if (spec.specVersion() != ResearchReportSpec.CURRENT_VERSION) {
            throw new ReportPayloadException(ReportPayloadException.Kind.SPEC_VERSION_UNSUPPORTED,
                    "specVersion=" + spec.specVersion() + " 不受支持（当前契约仅支持 "
                            + ResearchReportSpec.CURRENT_VERSION + "）");
        }
        if (spec.title() == null || spec.title().isBlank()) {
            throw new ReportPayloadException(ReportPayloadException.Kind.TITLE_BLANK,
                    "title 不能为空（研报标题必填）");
        }
        if (spec.contentMd() == null || spec.contentMd().isBlank()) {
            throw new ReportPayloadException(ReportPayloadException.Kind.CONTENT_BLANK,
                    "contentMd 不能为空（研报正文必填，须含「## 关键假设」章节）");
        }
        int contentBytes = spec.contentMd().getBytes(StandardCharsets.UTF_8).length;
        if (contentBytes > draftMaxBytes) {
            throw new ReportPayloadException(ReportPayloadException.Kind.CONTENT_OVERSIZE,
                    "contentMd " + contentBytes + " 字节超过上限 " + draftMaxBytes + " 字节（UTF-8 口径）");
        }
        return spec;
    }

    /**
     * 字符串字面量内裸控制字符预清洗（Ruling-21 修复②）：真 LLM 实证（S1 taskId=13，
     * 267743 tokens 跑例——首解与格式回炉重试双败同错）模型会在围栏 JSON 的字符串值内输出
     * <b>未转义</b>的控制字符（Jackson 报「Illegal unquoted character ((CTRL-CHAR, code 10))」，
     * 长 markdown 正文几乎必然带裸换行）。单遍状态机：跟踪「字符串内/外 + 转义态」——
     * 字符串内的裸 CTRL（&lt;0x20，含 \n \r \t \b \f）替换为对应转义序列（其余
     * {@code \\u00XX}）；字符串外的结构性裸换行是合法 JSON 空白，原样保留；已转义序列
     * （前导反斜杠）不动。只修「值内裸控制字符」这一类实证形态，不重排/不重写其余内容。
     */
    static String escapeRawControlCharsInStrings(String json) {
        StringBuilder out = new StringBuilder(json.length() + 16);
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString && !escaped && c < 0x20) {
                switch (c) {
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    case '\b' -> out.append("\\b");
                    case '\f' -> out.append("\\f");
                    default -> out.append(String.format("\\u%04x", (int) c));
                }
                continue;
            }
            out.append(c);
            if (escaped) {
                escaped = false;
            } else if (inString && c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            }
        }
        return out.toString();
    }
}
