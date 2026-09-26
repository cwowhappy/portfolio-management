package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.FundingRound;
import com.portfolio.invest.domain.industry.UnlistedCompany;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** V19 industry_unlisted_company 映射。latest_round 存枚举名字符串（域与实体分离，映射手写）。 */
@Entity
@Table(name = "industry_unlisted_company")
public class IndustryUnlistedCompanyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "industry_code", nullable = false, length = 16)
    private String industryCode;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Column(name = "segment", length = 64)
    private String segment;

    @Column(name = "latest_round", nullable = false, length = 16)
    private String latestRound;

    @Column(name = "last_funding_date")
    private LocalDate lastFundingDate;

    @Column(name = "total_funding_yi", precision = 14, scale = 2)
    private BigDecimal totalFundingYi;

    @Column(name = "summary", length = 256)
    private String summary;

    @Column(name = "source_note", length = 128)
    private String sourceNote;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected IndustryUnlistedCompanyJpaEntity() {}

    public static IndustryUnlistedCompanyJpaEntity fromDomain(UnlistedCompany c) {
        IndustryUnlistedCompanyJpaEntity e = new IndustryUnlistedCompanyJpaEntity();
        e.id = c.id();
        e.industryCode = c.industryCode();
        e.companyName = c.companyName();
        e.segment = c.segment();
        e.latestRound = c.latestRound().name();
        e.lastFundingDate = c.lastFundingDate();
        e.totalFundingYi = c.totalFundingYi();
        e.summary = c.summary();
        e.sourceNote = c.sourceNote();
        // 列 NOT NULL DEFAULT now()：域侧未携带时间戳（如插入路径）时以映射时刻兜底
        e.updatedAt = c.updatedAt() != null ? c.updatedAt() : Instant.now();
        return e;
    }

    public UnlistedCompany toDomain() {
        return new UnlistedCompany(id, industryCode, companyName, segment,
                FundingRound.valueOf(latestRound), lastFundingDate, totalFundingYi,
                summary, sourceNote, updatedAt);
    }

    /** upsert 命中幂等键时更新非键字段（industryCode+companyName 不动，id 保留）。 */
    void applyNonKeyFieldsFrom(IndustryUnlistedCompanyJpaEntity incoming) {
        this.segment = incoming.segment;
        this.latestRound = incoming.latestRound;
        this.lastFundingDate = incoming.lastFundingDate;
        this.totalFundingYi = incoming.totalFundingYi;
        this.summary = incoming.summary;
        this.sourceNote = incoming.sourceNote;
        this.updatedAt = incoming.updatedAt;
    }
}
