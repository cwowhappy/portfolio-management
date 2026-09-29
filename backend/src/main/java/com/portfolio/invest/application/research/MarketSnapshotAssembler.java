package com.portfolio.invest.application.research;

import com.portfolio.invest.application.alert.StockMetric;
import com.portfolio.invest.application.alert.ValuationDailyPort;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.market.MarketDataException;
import com.portfolio.invest.domain.market.Quote;
import com.portfolio.invest.domain.research.MarketSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 行情/估值快照组装（D10，P3-T4）：{@link com.portfolio.invest.domain.research.FalsifierEvaluator}
 * 的唯一取数侧——close 取 {@link MarketDataService} 最近价（东财口径），pe/pb 取
 * stock_valuation_daily（{@link ValuationDailyPort} 最新交易日）。
 *
 * <p>缺数据语义：任一口径不可得 → 对应值 null（evaluator skipped，不自动命中，D10）；
 * 行情源异常同样吞掉转 null（fail-safe，页面判定不该被行情抖动打断）。
 * priceNote 口径（Ruling-17）：pe/pb 任一可得 →「东财收盘及估值 yyyy-MM-dd」，
 * 仅 close →「东财收盘 yyyy-MM-dd」；日期经注入 Clock 定（A3，禁止直取系统时钟）。
 */
@Service
public class MarketSnapshotAssembler {

    private static final Logger log = LoggerFactory.getLogger(MarketSnapshotAssembler.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MarketDataService marketData;
    private final ValuationDailyPort valuationDaily;
    private final Clock clock;

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口，照 OrchestratingMarketDataService 先例）。 */
    @Autowired
    public MarketSnapshotAssembler(MarketDataService marketData, ValuationDailyPort valuationDaily) {
        this(marketData, valuationDaily, Clock.system(ZONE));
    }

    /** 测试注入：固定时钟（priceNote 日期确定性）。 */
    MarketSnapshotAssembler(MarketDataService marketData, ValuationDailyPort valuationDaily, Clock clock) {
        this.marketData = marketData;
        this.valuationDaily = valuationDaily;
        this.clock = clock;
    }

    /** 组装标的最近快照（两源独立取数，互不阻断）。 */
    public MarketSnapshot assemble(String stockCode) {
        LocalDate today = LocalDate.now(clock);
        BigDecimal close = fetchClose(stockCode);
        StockMetric metric = fetchValuation(stockCode);
        BigDecimal pe = metric == null ? null : metric.peTtm();
        BigDecimal pb = metric == null ? null : metric.pb();
        String note = (pe != null || pb != null)
                ? "东财收盘及估值 " + today
                : "东财收盘 " + today;
        return new MarketSnapshot(close, pe, pb, note);
    }

    /** 行情最近价：源异常/无该票 → null（价格谓词跳过，不自动命中）。 */
    private BigDecimal fetchClose(String stockCode) {
        try {
            Quote quote = marketData.quote(stockCode);
            return quote == null ? null : BigDecimal.valueOf(quote.price());
        } catch (MarketDataException e) {
            log.debug("快照组装：标的 {} 行情不可得（{}），close 置空", stockCode, e.getMessage());
            return null;
        }
    }

    /** 估值快照：stock_valuation_daily 最新交易日该票行；无表数据/无该票行 → null。 */
    private StockMetric fetchValuation(String stockCode) {
        return valuationDaily.latestTradingDay()
                .map(day -> valuationDaily.snapshots(day, List.of(stockCode)).get(stockCode))
                .orElse(null);
    }
}
