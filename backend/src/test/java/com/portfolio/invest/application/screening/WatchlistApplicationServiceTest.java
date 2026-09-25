package com.portfolio.invest.application.screening;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.screening.ScreeningErrorCode;
import com.portfolio.invest.domain.screening.ScreeningException;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import com.portfolio.invest.domain.screening.WatchlistItem;
import com.portfolio.invest.domain.screening.WatchlistRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WatchlistApplicationServiceTest {

    private final WatchlistRepository watchlist = mock(WatchlistRepository.class);
    private final ScreeningRepository screening = mock(ScreeningRepository.class);
    private final MarketDataService market = mock(MarketDataService.class);
    private final WatchlistApplicationService service =
            new WatchlistApplicationService(watchlist, screening, market);

    private static StockScreeningResult row(String code, String name, String industry) {
        return new StockScreeningResult(code, name, "801780", industry,
                new BigDecimal("5.6"), new BigDecimal("0.62"), new BigDecimal("5.4"),
                null, null, null, null, null, null, null, new BigDecimal("1000000000000"), null);
    }

    private static Quote quote(String code, double price) {
        return new Quote(code, "名", price, 0, 0, price, price, price, price, 0, 0, null, null, "2026-09-16 15:00:00");
    }

    @DisplayName("列表：快照行 join 实时现价，缺价与缺行都安全")
    @Test
    void givenWatchlistWithQuotes_whenList_thenMergedRows() {
        when(watchlist.findByUserId(1L)).thenReturn(List.of(
                new WatchlistItem(1L, 1L, "601398", Instant.parse("2026-09-16T00:00:00Z")),
                new WatchlistItem(2L, 1L, "600519", Instant.parse("2026-09-15T00:00:00Z"))));
        when(screening.findStocksByCodes(any())).thenReturn(List.of(
                row("601398", "工商银行", "银行"), row("600519", "贵州茅台", "食品饮料")));
        when(market.quoteBatch(any())).thenReturn(Map.of("601398", quote("601398", 5.6)));

        var rows = service.list(1L);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).stockCode()).isEqualTo("601398"); // addedAt 倒序由仓储保证
        assertThat(rows.get(0).stockName()).isEqualTo("工商银行");
        assertThat(rows.get(0).price()).isEqualByComparingTo("5.6");
        assertThat(rows.get(1).price()).isNull(); // 无行情 → null，前端显示 —
    }

    @DisplayName("空自选不触发快照与行情查询")
    @Test
    void givenEmptyWatchlist_whenList_thenNoDownstreamCalls() {
        when(watchlist.findByUserId(1L)).thenReturn(List.of());
        assertThat(service.list(1L)).isEmpty();
        verify(screening, never()).findStocksByCodes(any());
        verify(market, never()).quoteBatch(any());
    }

    @DisplayName("添加：不存在的代码拒绝；已存在幂等；超限拒绝")
    @Test
    void givenVariousStates_whenAdd_thenValidateIdempotentLimit() {
        when(screening.findStocksByCodes(List.of("999999"))).thenReturn(List.of());
        assertThatThrownBy(() -> service.add(1L, "999999"))
                .isInstanceOfSatisfying(ScreeningException.class,
                        e -> assertThat(e.code()).isEqualTo(ScreeningErrorCode.INVALID_STOCK));

        when(screening.findStocksByCodes(List.of("601398")))
                .thenReturn(List.of(row("601398", "工商银行", "银行")));
        when(watchlist.existsByUserIdAndStockCode(1L, "601398")).thenReturn(true);
        service.add(1L, "601398"); // 幂等：不再校验上限、不重复保存
        verify(watchlist, never()).save(any());

        when(watchlist.existsByUserIdAndStockCode(1L, "600519")).thenReturn(false);
        when(screening.findStocksByCodes(List.of("600519")))
                .thenReturn(List.of(row("600519", "贵州茅台", "食品饮料")));
        when(watchlist.countByUserId(1L)).thenReturn((long) WatchlistItem.MAX_SIZE);
        assertThatThrownBy(() -> service.add(1L, "600519"))
                .isInstanceOfSatisfying(ScreeningException.class,
                        e -> assertThat(e.code()).isEqualTo(ScreeningErrorCode.WATCHLIST_LIMIT_EXCEEDED));
    }

    @DisplayName("添加：ETF 代码不在股票快照但在 etf_basic → 放行保存")
    @Test
    void givenEtfCodeNotInStockSnapshotButInEtfBasic_whenAdd_thenSaved() {
        when(watchlist.existsByUserIdAndStockCode(1L, "518880")).thenReturn(false);
        when(screening.findStocksByCodes(List.of("518880"))).thenReturn(List.of());
        when(screening.existsFund("518880")).thenReturn(true);
        when(watchlist.countByUserId(1L)).thenReturn(0L);

        service.add(1L, "518880");

        verify(watchlist).save(any()); // ETF 无股票快照行也允许加入自选
    }

    @DisplayName("添加：股票快照与 etf_basic 都不在 → INVALID_STOCK 照旧")
    @Test
    void givenUnknownEverywhere_whenAdd_thenInvalidStock() {
        when(watchlist.existsByUserIdAndStockCode(1L, "999999")).thenReturn(false);
        when(screening.findStocksByCodes(List.of("999999"))).thenReturn(List.of());
        when(screening.existsFund("999999")).thenReturn(false);

        assertThatThrownBy(() -> service.add(1L, "999999"))
                .isInstanceOfSatisfying(ScreeningException.class,
                        e -> assertThat(e.code()).isEqualTo(ScreeningErrorCode.INVALID_STOCK));
        verify(watchlist, never()).save(any());
    }

    @DisplayName("移除：幂等（不存在亦成功）")
    @Test
    void whenRemove_thenAlwaysOk() {
        service.remove(1L, "600519");
        verify(watchlist).deleteByUserIdAndStockCode(1L, "600519");
    }
}
