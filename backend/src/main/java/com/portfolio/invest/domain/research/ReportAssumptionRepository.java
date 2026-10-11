package com.portfolio.invest.domain.research;

import java.util.List;

/**
 * 研报关键假设仓库端口（MS-32，设计规格 §3.5）：随定稿一次性落库，
 * UNIQUE(report_id, seq) 由 V8 兜底；anchorId 可空 = 门未绑定假设。
 */
public interface ReportAssumptionRepository {

    /** 按研报批量落假设（append-only，不做整替删除）。 */
    void saveAll(Long reportId, List<ReportAssumption> assumptions);

    /** 按研报取全部假设，seq 升序（陈述序）。 */
    List<ReportAssumption> findByReportId(Long reportId);
}
