package com.portfolio.invest.domain.research;

import java.util.List;

/**
 * 研报锚点仓库端口（MS-32，设计规格 §3.8）：锚点行创建后不可变（审计真相），
 * 过期状态为打开时重查的瞬态计算，不落库——本端口只做一次性写入与按研报读取。
 */
public interface ReportAnchorRepository {

    /** 按研报批量落锚点（append-only，不做整替删除——审计真相语义）。 */
    void saveAll(Long reportId, List<ReportAnchor> anchors);

    /** 按研报取全部锚点，id 升序（落库序）。 */
    List<ReportAnchor> findByReportId(Long reportId);
}
