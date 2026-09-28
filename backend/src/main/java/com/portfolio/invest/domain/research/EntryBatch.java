package com.portfolio.invest.domain.research;

import java.math.BigDecimal;

/**
 * 建仓批次（research_entry_batch 行，M16-F09）：分批价格区间/数量/金额/预计仓位占比。
 *
 * <p>record + 紧凑构造器校验（照仓库 domain record 先例）：priceLow ≤ priceHigh 且均非空非负、
 * quantity &gt; 0、ratio ∈ (0,1]；amount 可空（建仓计划为事前结构化记录，金额允许暂缺）。
 * Σratio ≤ 1 的列表级硬校验见 {@link EntryPlan#validateBatches(java.util.List)}。
 */
public record EntryBatch(int seq, BigDecimal priceLow, BigDecimal priceHigh, long quantity,
                         BigDecimal amount, BigDecimal ratio) {

    public EntryBatch {
        if (priceLow == null || priceHigh == null) {
            throw new ResearchException(ResearchErrorCode.BATCH_INVALID, "批次价格区间上下限均不能为空");
        }
        if (priceLow.signum() < 0 || priceHigh.signum() < 0) {
            throw new ResearchException(ResearchErrorCode.BATCH_INVALID, "批次价格不能为负");
        }
        if (priceLow.compareTo(priceHigh) > 0) {
            throw new ResearchException(ResearchErrorCode.BATCH_INVALID, "批次价格下限不能高于上限");
        }
        if (quantity <= 0) {
            throw new ResearchException(ResearchErrorCode.BATCH_INVALID, "批次数量必须为正");
        }
        if (ratio == null || ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
            throw new ResearchException(ResearchErrorCode.BATCH_INVALID, "批次仓位占比须在 (0,1] 区间");
        }
    }
}
