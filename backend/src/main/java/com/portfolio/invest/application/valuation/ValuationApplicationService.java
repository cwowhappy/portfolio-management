package com.portfolio.invest.application.valuation;

import com.portfolio.invest.domain.valuation.IndexValuation;
import com.portfolio.invest.domain.valuation.Percentile;
import com.portfolio.invest.domain.valuation.TreasuryYield;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

@Service
public class ValuationApplicationService {

    private static final String HS300 = "000300";
    /** 估值按交易日更新，overview/history 结果按当日日期为 key 加短 TTL 缓存，避免匿名请求反复全表扫描。 */
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final ValuationRepository repository;
    private final LongSupplier nowMillis;

    // TtlCache 位于 infrastructure.market，application 层按分包规范不可依赖，此处以同风格的最小 TTL 实现
    private final Map<String, CacheEntry> cache = new HashMap<>();

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口）。 */
    @org.springframework.beans.factory.annotation.Autowired
    public ValuationApplicationService(ValuationRepository repository) {
        this(repository, System::currentTimeMillis);
    }

    /** 测试注入：自定义时钟（避免真实墙钟等待）。 */
    ValuationApplicationService(ValuationRepository repository, LongSupplier nowMillis) {
        this.repository = repository;
        this.nowMillis = nowMillis;
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

        BigDecimal erp = erp(hs300, treasuries);
        BigDecimal erpPercentile = erpPercentile(erp, hs300);

        List<ValuationOverviewView.IndexValuationView> indices = indices();

        BigDecimal thermometer = thermometer(pePercentile, erpPercentile, breakerPercentile);
        boolean accumulating = snapshots.size() < 5;

        return new ValuationOverviewView(latest, pePercentile, pbPercentile, breakerPercentile,
                erp, erpPercentile, thermometer, indices, accumulating);
    }

    public List<IndustryValuationView> industries(String sort) {
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

    @SuppressWarnings("unchecked")
    private synchronized <T> T cached(String kind, Supplier<T> loader) {
        long now = nowMillis.getAsLong();
        String key = kind + ":" + LocalDate.now();
        CacheEntry hit = cache.get(key);
        if (hit != null && now <= hit.expiresAt()) {
            return (T) hit.value();
        }
        T value = loader.get();
        // 顺手清理过期键，防跨日累积
        cache.entrySet().removeIf(e -> e.getValue().expiresAt() < now);
        cache.put(key, new CacheEntry(value, now + CACHE_TTL.toMillis()));
        return value;
    }

    private record CacheEntry(Object value, long expiresAt) {}

    /** ERP = 沪深 300 股息率 − 10 年国债收益率；数据缺失返回 null。 */
    private BigDecimal erp(List<IndexValuation> hs300, List<TreasuryYield> treasuries) {
        IndexValuation latest = hs300.stream()
                .filter(i -> i.dividendYield() != null)
                .max(Comparator.comparing(IndexValuation::tradingDay))
                .orElse(null);
        var treasury = treasuries.stream()
                .max(Comparator.comparing(TreasuryYield::tradingDay))
                .orElse(null);
        if (latest == null || treasury == null) {
            return null;
        }
        return latest.dividendYield().subtract(treasury.yield10y()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal erpPercentile(BigDecimal erp, List<IndexValuation> hs300) {
        if (erp == null) {
            return null;
        }
        List<BigDecimal> erpHistory = hs300.stream()
                .filter(i -> i.dividendYield() != null)
                .map(IndexValuation::dividendYield)
                .toList();
        // 简化：以沪深 300 股息率分位近似 ERP 分位（ERP 与股息率同向）
        if (erpHistory.isEmpty()) {
            return null;
        }
        BigDecimal latestDiv = erpHistory.get(erpHistory.size() - 1);
        return Percentile.of(latestDiv, erpHistory);
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
