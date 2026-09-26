package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.IndustryChain;
import java.util.List;

/**
 * 产业链整包视图（设计规格 §四读侧 F11，量小整包返回）：tier 透出枚举名+中文 label 双字段
 * （照 UnlistedCompanyView 轮次双字段先例）；memberType 为 "LISTED"/"UNLISTED" 字符串
 * （表 CHECK 口径，不引枚举减少映射层）。
 */
public record ChainView(Long id, String name, String description, List<StageView> stages) {

    public record StageView(Long id, String tier, String tierLabel, String name, int sortOrder,
                            List<MemberView> members) {}

    public record MemberView(Long id, String memberType, String stockCode, Long unlistedCompanyId,
                             String displayName) {}

    public static ChainView from(IndustryChain chain) {
        return new ChainView(chain.id(), chain.name(), chain.description(),
                chain.stages().stream()
                        .map(s -> new StageView(s.id(), s.tier().name(), s.tier().label(), s.name(),
                                s.sortOrder(), s.members().stream()
                                        .map(m -> new MemberView(m.id(), m.memberType(), m.stockCode(),
                                                m.unlistedCompanyId(), m.displayName())).toList()))
                        .toList());
    }
}
