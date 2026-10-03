package com.portfolio.invest.infrastructure.persistence.intelligence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * intelligence_macro_calendar ORM 映射（V3；迁移 seed + SQL 人工维护，backend 只读）。
 *
 * <p>查询走 {@link IntelligenceMacroRepositoryImpl} 原生 SQL，本实体作为表结构的
 * JPA 注册载体（照 {@link IntelligenceNewsRawJpaEntity} 先例）。复合主键
 * (indicator, expected_date) 经 {@link Pk} 以 @IdClass 表达（仓库内首个复合键载体，
 * 字段名与实体 @Id 字段一一对应）。
 */
@Entity
@Table(name = "intelligence_macro_calendar")
@IdClass(IntelligenceMacroCalendarEntity.Pk.class)
public class IntelligenceMacroCalendarEntity {

    /** 指标码（CPI/PPI/PMI/LPR/AFMI）。 */
    @Id
    @Column(nullable = false, length = 16)
    private String indicator;

    /** 预期发布日（预期非承诺，F14）。 */
    @Id
    @Column(name = "expected_date", nullable = false)
    private LocalDate expectedDate;

    /** 发布频率（MONTH）。 */
    @Column(nullable = false, length = 8)
    private String frequency;

    /** 来源站点名（国家统计局 / 中国人民银行）。 */
    @Column(name = "source_site", nullable = false, length = 64)
    private String sourceSite;

    /** 日历行最近维护时间（非指标发布时间）。 */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IntelligenceMacroCalendarEntity() {}

    /**
     * 复合主键载体（@IdClass 契约：与实体 @Id 字段同名、无参构造、equals/hashCode）。
     */
    public static class Pk implements Serializable {

        private String indicator;
        private LocalDate expectedDate;

        public Pk() {}

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) {
                return false;
            }
            return Objects.equals(indicator, pk.indicator)
                    && Objects.equals(expectedDate, pk.expectedDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(indicator, expectedDate);
        }
    }
}
