package com.portfolio.invest.infrastructure.persistence.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * intelligence_news_raw ORM 映射（V3；collector 跨服务契约表，backend 只读）。
 *
 * <p>stock_tags 为源站任意 JSON（对象数组，如 {@code [{"code":"600519"}]}），
 * 映射为不解释的 JSON 文本（backend 只读不写，无需 JdbcTypeCode 序列化路径）。
 * 检索/游标/清理走 {@link IntelligenceNewsRepositoryImpl} 原生 SQL（trgm/JSONB
 * containment 无法用 JPQL 表达），本实体作为表结构的 JPA 注册载体供后续任务与
 * Hibernate 元数据消费。
 */
@Entity
@Table(name = "intelligence_news_raw")
public class IntelligenceNewsRawJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    @Column(columnDefinition = "text")
    private String url;

    /** 源站标的标签原样 JSON 文本（JSONB，对象数组）。 */
    @Column(name = "stock_tags", nullable = false, columnDefinition = "jsonb")
    private String stockTags;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    protected IntelligenceNewsRawJpaEntity() {}
}
