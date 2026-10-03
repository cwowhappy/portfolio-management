package com.portfolio.invest.infrastructure.persistence.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * intelligence_announcement ORM 映射（V3；collector 跨服务契约表，backend 只读）。
 *
 * <p>检索/游标走 {@link IntelligenceAnnouncementRepositoryImpl} 原生 SQL（trgm/直列过滤
 * 无法用 JPQL 表达），本实体作为表结构的 JPA 注册载体供后续任务与 Hibernate 元数据消费。
 */
@Entity
@Table(name = "intelligence_announcement")
public class IntelligenceAnnouncementJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Column(name = "stock_code", nullable = false, length = 12)
    private String stockCode;

    @Column(name = "stock_name", length = 32)
    private String stockName;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    /** 源站栏目（决策 #24 栏目映射的预判输入）。 */
    @Column(name = "ann_type_source", length = 64)
    private String annTypeSource;

    /** 栏目映射预判的重大类型（采集侧，§2.3-6）。 */
    @Column(nullable = false)
    private boolean major;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(name = "pdf_url", columnDefinition = "text")
    private String pdfUrl;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    protected IntelligenceAnnouncementJpaEntity() {}
}
