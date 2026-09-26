package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.FundingEvent;
import com.portfolio.invest.domain.industry.FundingRound;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** V19 industry_funding_event 映射。round 存枚举名字符串（域与实体分离，映射手写）。 */
@Entity
@Table(name = "industry_funding_event")
public class IndustryFundingEventJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Column(name = "round", nullable = false, length = 16)
    private String round;

    @Column(name = "amount_yi", precision = 14, scale = 2)
    private BigDecimal amountYi;

    @Column(name = "investors", length = 256)
    private String investors;

    @Column(name = "industry_code", nullable = false, length = 16)
    private String industryCode;

    @Column(name = "segment", length = 64)
    private String segment;

    @Column(name = "source_title", nullable = false, length = 256)
    private String sourceTitle;

    @Column(name = "source_url", length = 512)
    private String sourceUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected IndustryFundingEventJpaEntity() {}

    public static IndustryFundingEventJpaEntity fromDomain(FundingEvent e) {
        IndustryFundingEventJpaEntity entity = new IndustryFundingEventJpaEntity();
        entity.id = e.id();
        entity.eventDate = e.eventDate();
        entity.companyName = e.companyName();
        entity.round = e.round().name();
        entity.amountYi = e.amountYi();
        entity.investors = e.investors();
        entity.industryCode = e.industryCode();
        entity.segment = e.segment();
        entity.sourceTitle = e.sourceTitle();
        entity.sourceUrl = e.sourceUrl();
        // 列 NOT NULL DEFAULT now()：域侧未携带时间戳（如插入路径）时以映射时刻兜底
        entity.createdAt = e.createdAt() != null ? e.createdAt() : Instant.now();
        return entity;
    }

    public FundingEvent toDomain() {
        return new FundingEvent(id, eventDate, companyName, FundingRound.valueOf(round),
                amountYi, investors, industryCode, segment, sourceTitle, sourceUrl, createdAt);
    }

    /** upsert 命中幂等键时更新非键字段（eventDate+companyName+round 不动，id 保留）。 */
    void applyNonKeyFieldsFrom(IndustryFundingEventJpaEntity incoming) {
        this.amountYi = incoming.amountYi;
        this.investors = incoming.investors;
        this.industryCode = incoming.industryCode;
        this.segment = incoming.segment;
        this.sourceTitle = incoming.sourceTitle;
        this.sourceUrl = incoming.sourceUrl;
    }
}
