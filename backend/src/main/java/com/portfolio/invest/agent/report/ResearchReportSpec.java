package com.portfolio.invest.agent.report;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 深度研究工作流研报 payload 契约（MS-32 B6，设计规格 §3.4；形态沿
 * {@code agent/research/ResearchDraftSpec} sealed interface + specVersion 模式）。
 * 由 Agent 草稿轮最终消息的 {@code ```research-report} 围栏 JSON 反序列化而来，
 * 经 {@link ReportPayloadParser} 六态校验后交校验门/落库。
 *
 * <p>与 ResearchDraftSpec 的差异：消费方是后端解析器而非前端 zod（无 null 裁剪诉求，
 * 不加 NON_NULL）；假设条目与锚点绑定不在 payload 里——LLM 只负责「关键假设」章节文本
 * （每条含来源数字陈述），绑定由门后处理自动完成（设计规格 §3.4/§3.7）。
 */
public sealed interface ResearchReportSpec permits ResearchReportSpec.V1 {

    int specVersion();

    /** 当前契约版本，工厂统一盖入；解析侧非 1 → SPEC_VERSION_UNSUPPORTED。 */
    int CURRENT_VERSION = 1;

    /**
     * v1 契约：title/contentMd 必填非空白（TITLE_BLANK/CONTENT_BLANK 拦），contentMd
     * 为全文 markdown（含「## 关键假设」编号章节），limitations 数据局限注记（可空——
     * 与 V8 research_report.limitations 可空列对齐）。
     */
    record V1(@JsonProperty("specVersion") int specVersion,
              @JsonProperty("title") String title,
              @JsonProperty("contentMd") String contentMd,
              @JsonProperty("limitations") String limitations) implements ResearchReportSpec {}

    static V1 v1(String title, String contentMd, String limitations) {
        return new V1(CURRENT_VERSION, title, contentMd, limitations);
    }
}
