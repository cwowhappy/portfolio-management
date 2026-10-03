package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.domain.intelligence.AnnouncementMetrics;
import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.ExtractStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * intelligence_announcement_extract ORM 映射（V3；UNIQUE(announcement_id) 一对一）。
 *
 * <p>backend 经 {@link IntelligenceAnnouncementRepositoryImpl#upsertExtract} 以
 * INSERT … ON CONFLICT 独占写入（应用层 Task 5 判定 SUCCESS/FAILED）；读取走仓库原生
 * SQL，本实体作为表结构的 JPA 注册载体供后续任务与 Hibernate 元数据消费。JSONB 列照
 * ResearchCheckRecordJpaEntity.items 先例（{@code @JdbcTypeCode(SqlTypes.JSON)} +
 * 领域 record/枚举）：metrics 直映射六字段契约，ann_types 为 AnnouncementType 枚举名数组。
 */
@Entity
@Table(name = "intelligence_announcement_extract")
public class IntelligenceAnnouncementExtractJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "announcement_id", nullable = false)
    private Long announcementId;

    /** 六字段业绩要点（未披露字段 null 且进 undisclosed，严禁编造）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private AnnouncementMetrics metrics;

    /** 公告类型标签（栏目预判 ∪ LLM 精判的枚举名数组）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ann_types", columnDefinition = "jsonb")
    private List<AnnouncementType> annTypes;

    @Column(name = "pdf_text", columnDefinition = "text")
    private String pdfText;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private ExtractStatus status;

    @Column(length = 64)
    private String model;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    protected IntelligenceAnnouncementExtractJpaEntity() {}
}
