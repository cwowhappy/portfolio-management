package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.ReportTokenQuota;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

/**
 * 用户日 token 配额行（report_token_quota，V8 建表，设计规格 §3.9）。复合主键
 * (user_id, quota_date) 经 {@link Pk} 以 @IdClass 表达（照 IntelligenceMacroCalendarEntity
 * 先例，字段名与实体 @Id 字段一一对应）。写入不走本实体——settle 为原生 upsert 累加，
 * 本实体只承载按复合键读取。
 */
@Entity
@Table(name = "report_token_quota")
@IdClass(ReportTokenQuotaJpaEntity.Pk.class)
public class ReportTokenQuotaJpaEntity {

    @Id
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Id
    @Column(name = "quota_date", nullable = false)
    private LocalDate quotaDate;

    @Column(name = "tokens_used", nullable = false)
    private long tokensUsed;

    protected ReportTokenQuotaJpaEntity() {}

    public ReportTokenQuota toDomain() {
        return new ReportTokenQuota(userId, quotaDate, tokensUsed);
    }

    /**
     * 复合主键载体（@IdClass 契约：与实体 @Id 字段同名、无参构造、equals/hashCode）。
     */
    public static class Pk implements Serializable {

        private Long userId;
        private LocalDate quotaDate;

        public Pk() {}

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Pk pk)) {
                return false;
            }
            return Objects.equals(userId, pk.userId) && Objects.equals(quotaDate, pk.quotaDate);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, quotaDate);
        }
    }
}
