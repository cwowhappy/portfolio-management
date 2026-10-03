package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.domain.intelligence.ExtractStatus;
import com.portfolio.invest.domain.intelligence.PolicyConfidence;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import com.portfolio.invest.domain.intelligence.PolicyStrength;
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
 * intelligence_policy_event ORM 映射（V3；UNIQUE(policy_raw_id) 一对一，FK 无级联删除——
 * 政策长期保留语义）。backend 经 {@link IntelligencePolicyRepositoryImpl#upsertExtract} 以
 * INSERT … ON CONFLICT 独占写入；读取走仓库原生 SQL，本实体作为表结构的 JPA 注册载体。
 *
 * <p>表无 is_policy 列：非政策兜底行以哨兵 summary 编码（读取侧
 * {@link com.portfolio.invest.domain.intelligence.PolicyEvent#of} 派生 isPolicy）。
 */
@Entity
@Table(name = "intelligence_policy_event")
public class IntelligencePolicyEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "policy_raw_id", nullable = false)
    private Long policyRawId;

    /** 政策取向（V3 ck_policy_dir 受控枚举）。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PolicyDirection direction;

    /** 政策力度（V3 ck_policy_strength 受控枚举）。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private PolicyStrength strength;

    /** 影响领域（JSONB 字符串数组）。 */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affected_areas", columnDefinition = "jsonb")
    private List<String> affectedAreas;

    @Column(columnDefinition = "text")
    private String summary;

    /** 置信度（LOW=低置信标注，F12）。 */
    @Enumerated(EnumType.STRING)
    @Column(length = 8)
    private PolicyConfidence confidence;

    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    private ExtractStatus status;

    @Column(length = 64)
    private String model;

    @Column(name = "extracted_at")
    private Instant extractedAt;

    protected IntelligencePolicyEventJpaEntity() {}
}
