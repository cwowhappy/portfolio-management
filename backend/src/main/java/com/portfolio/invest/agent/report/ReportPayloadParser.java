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
 * 校验序：围栏 → JSON → specVersion → title → contentMd → 字节上限（UTF-8 字节口径，
 * contentMd 独计，恰超 = 字节数 &gt; 上限，等于上限放行）。
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
            spec = MAPPER.readValue(fence.group(1), ResearchReportSpec.V1.class);
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
}
