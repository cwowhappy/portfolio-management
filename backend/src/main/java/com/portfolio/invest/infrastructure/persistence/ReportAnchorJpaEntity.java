package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ReportAnchor;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 研报锚点行（research_report_anchor，V8 建表，设计规格 §3.8）：数字片段的审计真相，
 * 创建后不可变（无任何更新路径）。args JSONB 以 Map 直存（照 McpUserConfigJpaEntity.disabledTools
 * 的 SqlTypes.JSON 先例；DB jsonb 保证语义有效性，读回键序归一——按语义比较不按字节串）。
 */
@Entity
@Table(name = "research_report_anchor")
public class ReportAnchorJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "report_id", nullable = false)
    private Long reportId;

    @Column(nullable = false, columnDefinition = "text")
    private String snippet;

    @Column(nullable = false)
    private int occ;

    /** 真值三态 VERIFIED/SOURCED/UNVERIFIED（沿 agent TrustVerdict.name()，DB CHECK 兜底）。 */
    @Column(nullable = false, length = 16)
    private String state;

    @Column(name = "tool_name", columnDefinition = "text")
    private String toolName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> args;

    @Column(name = "as_of")
    private Instant asOf;

    /** DATA/GENERATED/CALL（沿 ToolInvocation）。 */
    @Column(name = "as_of_kind", columnDefinition = "text")
    private String asOfKind;

    @Column(columnDefinition = "text")
    private String raw;

    protected ReportAnchorJpaEntity() {}

    /** reportId 以仓库 saveAll 入参为准（与 record 内值一致性由用例层保证）。 */
    public static ReportAnchorJpaEntity fromDomain(Long reportId, ReportAnchor a) {
        ReportAnchorJpaEntity entity = new ReportAnchorJpaEntity();
        entity.id = a.id();
        entity.reportId = reportId;
        entity.snippet = a.snippet();
        entity.occ = a.occ();
        entity.state = a.state();
        entity.toolName = a.toolName();
        entity.args = a.args();
        entity.asOf = a.asOf();
        entity.asOfKind = a.asOfKind();
        entity.raw = a.raw();
        return entity;
    }

    public ReportAnchor toDomain() {
        return new ReportAnchor(id, reportId, snippet, occ, state, toolName, args, asOf, asOfKind, raw);
    }
}
