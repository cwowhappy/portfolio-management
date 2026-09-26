package com.portfolio.invest.domain.industry;

import java.util.List;

/**
 * 产业链环节（设计规格 §三）：tier 三档层级 + 同 tier 内 sortOrder 排序；成员按 id 序。
 * id 由库生成（插入时为 null）。
 */
public record ChainStage(Long id, ChainTier tier, String name, int sortOrder,
                         List<ChainMember> members) {
}
