package com.portfolio.invest.domain.intelligence;

import java.util.List;

/**
 * 情报域分页结果（D20）：条目 + 总数 + 回显页码/页大小。
 *
 * <p>与 {@link PageQuery} 成对落在 domain（仓库接口出参）；P4 的 API 信封
 * {@code PageView<T>} 由 application/IntelligenceViews 从本 record 映射。
 * items 防御性拷贝为不可变列表。
 */
public record PageResult<T>(List<T> items, long total, int page, int pageSize) {

    public PageResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
