package com.portfolio.invest.domain.screening;

import java.time.Instant;

/** 自选观察列表项：登录用户的观察股票（与持仓互不依赖）。 */
public record WatchlistItem(Long id, Long userId, String stockCode, Instant addedAt) {

    /** 每用户自选上限（spec：上限 100 只/用户）。 */
    public static final int MAX_SIZE = 100;
}
