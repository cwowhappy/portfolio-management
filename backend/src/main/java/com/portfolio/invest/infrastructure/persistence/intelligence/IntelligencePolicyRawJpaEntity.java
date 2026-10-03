package com.portfolio.invest.infrastructure.persistence.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * intelligence_policy_raw ORM 映射（V3；collector 跨服务契约表，backend 只读）。
 *
 * <p>检索/游标走 {@link IntelligencePolicyRepositoryImpl} 原生 SQL（trgm 为 PG 方言），
 * 本实体作为表结构的 JPA 注册载体供后续任务与 Hibernate 元数据消费（照
 * {@link IntelligenceNewsRawJpaEntity} 先例）。长期保留：无清理任务、无级联删除。
 * 业务键 UNIQUE (source, external_id)。
 */
@Entity
@Table(name = "intelligence_policy_raw")
public class IntelligencePolicyRawJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 采集源标识（pboc/csrc/mof/stats）。 */
    @Column(nullable = false, length = 16)
    private String source;

    @Column(name = "external_id", nullable = false, length = 64)
    private String externalId;

    @Column(nullable = false, columnDefinition = "text")
    private String title;

    /** 原文链接（事件可回溯）。 */
    @Column(columnDefinition = "text")
    private String url;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt;

    /** 政策正文全文（collector 两跳抓取，截 8000 字）。 */
    @Column(name = "content_text", columnDefinition = "text")
    private String contentText;

    protected IntelligencePolicyRawJpaEntity() {}
}
