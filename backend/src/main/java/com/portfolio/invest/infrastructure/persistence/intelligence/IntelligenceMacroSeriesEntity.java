package com.portfolio.invest.infrastructure.persistence.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * intelligence_macro_series ORM 映射（V3；collector 跨服务契约表，backend 只读）。
 *
 * <p>查询走 {@link IntelligenceMacroRepositoryImpl} 原生 SQL（DISTINCT ON 为 PG 方言），
 * 本实体作为表结构的 JPA 注册载体供后续任务与 Hibernate 元数据消费（照
 * {@link IntelligenceNewsRawJpaEntity} 先例）。业务键 UNIQUE (indicator, period)。
 */
@Entity
@Table(name = "intelligence_macro_series")
public class IntelligenceMacroSeriesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 指标码（CPI/PPI/PMI/LPR/AFMI；国债收益率复用 treasury_yield_curve 不在此表）。 */
    @Column(nullable = false, length = 16)
    private String indicator;

    /** 期别（月 2026-09 / 日 2026-09-29，同指标格式恒定）。 */
    @Column(nullable = false, length = 10)
    private String period;

    /** 期别类型（MONTH / DAY，V3 ck_macro_period_type 约束）。 */
    @Column(name = "period_type", nullable = false, length = 8)
    private String periodType;

    /** 指标值（NUMERIC(18,4)）。 */
    @Column(nullable = false, precision = 18, scale = 4)
    private BigDecimal value;

    /** 同比（NUMERIC(10,4)，可空——源未给出时缺席）。 */
    @Column(precision = 10, scale = 4)
    private BigDecimal yoy;

    @Column(name = "source_url", columnDefinition = "text")
    private String sourceUrl;

    @Column(name = "source_note", length = 255)
    private String sourceNote;

    protected IntelligenceMacroSeriesEntity() {}
}
