package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.ChainMember;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** V20 industry_chain_member 映射。memberType 存 "LISTED"/"UNLISTED" 字符串（表 CHECK 口径）。 */
@Entity
@Table(name = "industry_chain_member")
public class IndustryChainMemberJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "stage_id", nullable = false)
    private Long stageId;

    @Column(name = "member_type", nullable = false, length = 8)
    private String memberType;

    @Column(name = "stock_code", length = 16)
    private String stockCode;

    @Column(name = "unlisted_company_id")
    private Long unlistedCompanyId;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    protected IndustryChainMemberJpaEntity() {}

    /** 插入用工厂：stageId 由聚合保存流程提供（domain 记录不携带）。 */
    public static IndustryChainMemberJpaEntity fromDomain(Long stageId, ChainMember member) {
        IndustryChainMemberJpaEntity e = new IndustryChainMemberJpaEntity();
        e.id = member.id();
        e.stageId = stageId;
        e.memberType = member.memberType();
        e.stockCode = member.stockCode();
        e.unlistedCompanyId = member.unlistedCompanyId();
        e.displayName = member.displayName();
        return e;
    }

    public ChainMember toDomain() {
        return new ChainMember(id, memberType, stockCode, unlistedCompanyId, displayName);
    }
}
