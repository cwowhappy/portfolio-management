package com.portfolio.invest.domain.research;

/**
 * 研报关键假设（MS-32，设计规格 §二 research_report_assumption 行）：seq 在研报内 1 起始
 * （UNIQUE(report_id, seq)）；anchorId 可空 = 门未绑定假设（门语义见设计规格 §3.5）。
 * 紧凑构造器校验（照 {@link EntryBatch} record 先例）。
 */
public record ReportAssumption(Long id, Long reportId, int seq, String statement, Long anchorId) {

    public ReportAssumption {
        if (reportId == null) {
            throw new ResearchException(ResearchErrorCode.REPORT_REQUIRED, "归属研报不能为空");
        }
        if (seq < 1) {
            throw new ResearchException(ResearchErrorCode.ASSUMPTION_INVALID, "假设序号必须为 1 起始");
        }
        if (statement == null || statement.isBlank()) {
            throw new ResearchException(ResearchErrorCode.ASSUMPTION_INVALID, "假设陈述不能为空");
        }
    }
}
