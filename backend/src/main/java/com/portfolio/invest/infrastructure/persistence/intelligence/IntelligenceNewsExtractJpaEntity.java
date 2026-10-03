package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.domain.intelligence.Direction;
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
 * intelligence_news_extract ORM 映射（V3；UNIQUE(news_raw_id) 一对一）。
 *
 * <p>backend 经 {@link IntelligenceNewsRepositoryImpl#upsertExtract} 以
 * INSERT … ON CONFLICT 独占写入（应用层 Task 8 判定 SUCCESS/FAILED）；
 * 读取走仓库原生 SQL，本实体作为表结构的 JPA 注册载体供后续任务与
 * Hibernate 元数据消费。数组类 JSONB 列照 McpUserConfigJpaEntity.disabledTools
 * 先例（{@code @JdbcTypeCode(SqlTypes.JSON)} + List&lt;String&gt;）。
 */
@Entity
@Table(name = "intelligence_news_extract")
public class IntelligenceNewsExtractJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "news_raw_id", nullable = false)
    private Long newsRawId;

    @Column(name = "event_type", length = 32)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "stock_codes", columnDefinition = "jsonb")
    private List<String> stockCodes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "industry_codes", columnDefinition = "jsonb")
    private List<String> industryCodes;

    @Column(columnDefinition = "text")
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private Direction direction;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_numbers", columnDefinition = "jsonb")
    private List<String> keyNumbers;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private ExtractStatus status;

    @Column(length = 64)
    private String model;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    protected IntelligenceNewsExtractJpaEntity() {}
}
