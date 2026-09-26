package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.ChainStage;
import com.portfolio.invest.domain.industry.ChainTier;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.List;

/**
 * V20 industry_chain_stage 映射。tier 存枚举名字符串（域与实体分离，映射手写）；
 * members 不做关联映射——链组装在仓储 Impl 两步批查（照本包扁平实体先例）。
 */
@Entity
@Table(name = "industry_chain_stage")
public class IndustryChainStageJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chain_id", nullable = false)
    private Long chainId;

    @Column(name = "tier", nullable = false, length = 16)
    private String tier;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    protected IndustryChainStageJpaEntity() {}

    /** 插入用工厂：chainId 由聚合保存流程提供（domain 记录不携带）。 */
    public static IndustryChainStageJpaEntity fromDomain(Long chainId, ChainStage stage) {
        IndustryChainStageJpaEntity e = new IndustryChainStageJpaEntity();
        e.id = stage.id();
        e.chainId = chainId;
        e.tier = stage.tier().name();
        e.name = stage.name();
        e.sortOrder = stage.sortOrder();
        return e;
    }

    /** 行读取（members 由 Impl 批查组装；此处恒空表——勿用于断言成员）。 */
    public ChainStage toDomain() {
        return new ChainStage(id, ChainTier.valueOf(tier), name, sortOrder, List.of());
    }
}
