package com.portfolio.invest.application.screening;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.screening.ScreeningErrorCode;
import com.portfolio.invest.domain.screening.ScreeningException;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import com.portfolio.invest.domain.screening.WatchlistItem;
import com.portfolio.invest.domain.screening.WatchlistRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 自选观察列表用例：列表（快照 join 实时价）/ 添加（校验·幂等·上限）/ 移除（幂等）。 */
@Service
public class WatchlistApplicationService {

    private final WatchlistRepository watchlistRepository;
    private final ScreeningRepository screeningRepository;
    private final MarketDataService marketDataService;

    public WatchlistApplicationService(WatchlistRepository watchlistRepository,
                                       ScreeningRepository screeningRepository,
                                       MarketDataService marketDataService) {
        this.watchlistRepository = watchlistRepository;
        this.screeningRepository = screeningRepository;
        this.marketDataService = marketDataService;
    }

    public List<WatchlistItemView> list(Long userId) {
        List<WatchlistItem> items = watchlistRepository.findByUserId(userId);
        if (items.isEmpty()) {
            return List.of();
        }
        List<String> codes = items.stream().map(WatchlistItem::stockCode).toList();
        Map<String, StockScreeningResult> snapshot = screeningRepository.findStocksByCodes(codes).stream()
                .collect(Collectors.toMap(StockScreeningResult::stockCode, r -> r, (a, b) -> a));
        Map<String, Quote> quotes = marketDataService.quoteBatch(codes);
        return items.stream().map(item -> {
            var row = snapshot.get(item.stockCode());
            Quote q = quotes.get(item.stockCode());
            return new WatchlistItemView(item.stockCode(),
                    row == null ? null : row.stockName(),
                    row == null ? null : row.industryName(),
                    q == null ? null : BigDecimal.valueOf(q.price()),
                    row == null ? null : row.peTtm(),
                    row == null ? null : row.pb(),
                    row == null ? null : row.dividendYield(),
                    row == null ? null : row.totalMv(),
                    item.addedAt());
        }).toList();
    }

    @Transactional
    public void add(Long userId, String stockCode) {
        if (watchlistRepository.existsByUserIdAndStockCode(userId, stockCode)) {
            return; // 幂等：重复添加直接成功
        }
        // 存在性并集：股票最新快照 ∪ etf_basic 目录（ETF 代码不在股票快照，凭目录命中放行）
        if (screeningRepository.findStocksByCodes(List.of(stockCode)).isEmpty()
                && !screeningRepository.existsFund(stockCode)) {
            throw new ScreeningException(ScreeningErrorCode.INVALID_STOCK, "未知股票代码: " + stockCode);
        }
        if (watchlistRepository.countByUserId(userId) >= WatchlistItem.MAX_SIZE) {
            throw new ScreeningException(ScreeningErrorCode.WATCHLIST_LIMIT_EXCEEDED,
                    "自选已达上限 " + WatchlistItem.MAX_SIZE + " 只");
        }
        watchlistRepository.save(new WatchlistItem(null, userId, stockCode, Instant.now()));
    }

    @Transactional
    public void remove(Long userId, String stockCode) {
        watchlistRepository.deleteByUserIdAndStockCode(userId, stockCode);
    }
}
