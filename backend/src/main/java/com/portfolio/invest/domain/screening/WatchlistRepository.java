package com.portfolio.invest.domain.screening;

import java.util.List;

/** 自选仓库端口：归属过滤（userId）在用例层双重保障。 */
public interface WatchlistRepository {
    List<WatchlistItem> findByUserId(Long userId); // addedAt 倒序

    boolean existsByUserIdAndStockCode(Long userId, String stockCode);

    long countByUserId(Long userId);

    WatchlistItem save(WatchlistItem item);

    void deleteByUserIdAndStockCode(Long userId, String stockCode);
}
