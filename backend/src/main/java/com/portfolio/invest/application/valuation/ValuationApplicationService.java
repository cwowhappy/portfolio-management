package com.portfolio.invest.application.valuation;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.valuation.IndexValuation;
import com.portfolio.invest.domain.valuation.Percentile;
import com.portfolio.invest.domain.valuation.TreasuryYield;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class ValuationApplicationService {

    private static final String HS300 = "000300";

    private final ValuationRepository repository;
    private final ApplicationCache cache;
    /** 估值结果缓存 TTL（默认 5min；生产经 invest.app-cache.ttl 配置）。 */
    private final Duration cacheTtl;

    @Autowired
    public ValuationApplicationService(ValuationRepository repository, ApplicationCache cache, InvestProperties props) {
        this(repository, cache, props.getAppCache().getTtl());
    }

    public ValuationApplicationService(ValuationRepository repository, ApplicationCache cache) {
        this(repository, cache, Duration.ofMinutes(5));
    }

    ValuationApplicationService(ValuationRepository repository, ApplicationCache cache, Duration cacheTtl) {
        this.repository = repository;
        this.cache = cache;
        this.cacheTtl = cacheTtl;
    }

    public ValuationOverviewView overview() {
        return cached("overview", this::loadOverview);
    }

    private ValuationOverviewView loadOverview() {
        ValuationSnapshot latest = repository.findLatestSnapshot();
        List<ValuationSnapshot> snapshots = repository.findAllSnapshots();
        // 沪深300 与国债序列单次加载，erp 与 erpPercentile 复用（不再重复查询）
        List<IndexValuation> hs300 = repository.findIndexValuations(HS300);
        List<TreasuryYield> treasuries = repository.findAllTreasuryYields();

        List<BigDecimal> peHistory = snapshots.stream().map(ValuationSnapshot::peMedian).toList();
        List<BigDecimal> pbHistory = snapshots.stream().map(ValuationSnapshot::pbMedian).toList();
        List<BigDecimal> breakerHistory = snapshots.stream().map(ValuationSnapshot::netBreakerRatio).toList();

        BigDecimal pePercentile = latest == null ? null : Percentile.of(latest.peMedian(), peHistory);
        BigDecimal pbPercentile = latest == null ? null : Percentile.of(latest.pbMedian(), pbHistory);
        BigDecimal breakerPercentile = latest == null ? null : Percentile.of(latest.netBreakerRatio(), breakerHistory);

        // 真实 ERP 历史序列：按 tradingDay 对齐 hs300 股息率与 10Y 国债，逐日 ERP = dividendYield − yield10y
        List<BigDecimal> erpSeries = erpHistory(hs300, treasuries);
        BigDecimal erp = erpSeries.isEmpty() ? null : erpSeries.get(erpSeries.size() - 1);
        BigDecimal erpPercentile = erp == null ? null : Percentile.of(erp, erpSeries);

        List<ValuationOverviewView.IndexValuationView> indices = indices();

        BigDecimal thermometer = thermometer(pePercentile, erpPercentile, breakerPercentile);
        boolean accumulating = snapshots.size() < 5;

        ValuationOverviewView.SnapshotView latestView = latest == null ? null
                : new ValuationOverviewView.SnapshotView(
                        latest.tradingDay(), latest.peMedian(), latest.pbMedian(),
                        latest.netBreakerCount(), latest.netBreakerRatio());
        return new ValuationOverviewView(latestView, pePercentile, pbPercentile, breakerPercentile,
                erp, erpPercentile, thermometer, indices, accumulating);
    }

    public List<IndustryValuationView> industries(String sort) {
        String sortKey = sort == null ? "pe" : sort;
        return cached("industries:" + sortKey, () -> loadIndustries(sort));
    }

    private List<IndustryValuationView> loadIndustries(String sort) {
        ValuationSnapshot latest = repository.findLatestSnapshot();
        if (latest == null) {
            return List.of();
        }
        LocalDate day = latest.tradingDay();
        List<IndustryValuationView> views = repository.findIndustryValuationsByDay(day).stream()
                .map(i -> new IndustryValuationView(i.industryCode(), i.industryName(), i.pe(), i.pb(), i.roe(), i.dividendYield()))
                .toList();
        Comparator<IndustryValuationView> cmp = switch (sort == null ? "pe" : sort) {
            case "pb" -> Comparator.comparing(IndustryValuationView::pb, Comparator.nullsLast(BigDecimal::compareTo));
            case "roe" -> Comparator.comparing(IndustryValuationView::roe, Comparator.nullsLast(BigDecimal::compareTo));
            case "dividend" -> Comparator.comparing(IndustryValuationView::dividendYield, Comparator.nullsLast(BigDecimal::compareTo));
            default -> Comparator.comparing(IndustryValuationView::pe, Comparator.nullsLast(BigDecimal::compareTo));
        };
        return views.stream().sorted(cmp).toList();
    }

    public ValuationHistoryView history() {
        return cached("history", this::loadHistory);
    }

    private ValuationHistoryView loadHistory() {
        return new ValuationHistoryView(
                repository.findAllSnapshots(),
                repository.findAllTreasuryYields(),
                repository.findIndexValuations(HS300));
    }

    private <T> T cached(String kind, Supplier<T> loader) {
        // 应用级共享缓存（ApplicationCache 端口），key 带域前缀防冲突
        String key = "valuation:" + kind + ":" + LocalDate.now();
        T hit = cache.get(key);
        if (hit != null) {
            return hit;
        }
        T value = loader.get();
        cache.put(key, value, cacheTtl);
        return value;
    }

    /**
     * 真实 ERP 历史序列：按 tradingDay 对齐沪深 300 的股息率与 10Y 国债收益率，
     * 逐日 ERP = dividendYield − yield10y（按交易日升序，任一缺失的交易日跳过）。
     */
    private List<BigDecimal> erpHistory(List<IndexValuation> hs300, List<TreasuryYield> treasuries) {
        Map<LocalDate, BigDecimal> treasuryByDay = treasuries.stream()
                .filter(t -> t.yield10y() != null)
                .collect(Collectors.toMap(TreasuryYield::tradingDay, TreasuryYield::yield10y, (a, b) -> a));
        return hs300.stream()
                .filter(i -> i.dividendYield() != null)
                .filter(i -> treasuryByDay.containsKey(i.tradingDay()))
                .sorted(Comparator.comparing(IndexValuation::tradingDay))
                .map(i -> i.dividendYield().subtract(treasuryByDay.get(i.tradingDay())).setScale(2, RoundingMode.HALF_UP))
                .toList();
    }

    private List<ValuationOverviewView.IndexValuationView> indices() {
        return List.of("000016", "000300", "000905", "399006", "000688").stream()
                .map(code -> {
                    List<IndexValuation> history = repository.findIndexValuations(code);
                    if (history.isEmpty()) {
                        return new ValuationOverviewView.IndexValuationView(code, "", null, null, null, null, null);
                    }
                    IndexValuation latest = history.get(history.size() - 1);
                    List<BigDecimal> peHistory = history.stream().filter(i -> i.pe() != null).map(IndexValuation::pe).toList();
                    List<BigDecimal> pbHistory = history.stream().filter(i -> i.pb() != null).map(IndexValuation::pb).toList();
                    return new ValuationOverviewView.IndexValuationView(
                            latest.indexCode(), latest.indexName(), latest.pe(), latest.pb(), latest.dividendYield(),
                            Percentile.of(latest.pe(), peHistory), Percentile.of(latest.pb(), pbHistory));
                })
                .toList();
    }

    /** 温度计：0~100，越高越「贵/热」。权重：PE 分位 0.4 + ERP 反转 0.4 + 破净占比反转 0.2。 */
    private BigDecimal thermometer(BigDecimal pePercentile, BigDecimal erpPercentile, BigDecimal breakerPercentile) {
        if (pePercentile == null || erpPercentile == null || breakerPercentile == null) {
            return null;
        }
        BigDecimal invertedErp = BigDecimal.valueOf(100).subtract(erpPercentile);
        BigDecimal invertedBreaker = BigDecimal.valueOf(100).subtract(breakerPercentile);
        return pePercentile.multiply(new BigDecimal("0.4"))
                .add(invertedErp.multiply(new BigDecimal("0.4")))
                .add(invertedBreaker.multiply(new BigDecimal("0.2")))
                .setScale(0, RoundingMode.HALF_UP);
    }
}
