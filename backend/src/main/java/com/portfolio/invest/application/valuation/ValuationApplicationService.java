package com.portfolio.invest.application.valuation;

import com.portfolio.invest.domain.valuation.IndexValuation;
import com.portfolio.invest.domain.valuation.Percentile;
import com.portfolio.invest.domain.valuation.TreasuryYield;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

@Service
public class ValuationApplicationService {

    private static final String HS300 = "000300";

    private final ValuationRepository repository;

    public ValuationApplicationService(ValuationRepository repository) {
        this.repository = repository;
    }

    public ValuationOverviewView overview() {
        ValuationSnapshot latest = repository.findLatestSnapshot();
        List<ValuationSnapshot> snapshots = repository.findAllSnapshots();
        List<BigDecimal> peHistory = snapshots.stream().map(ValuationSnapshot::peMedian).toList();
        List<BigDecimal> pbHistory = snapshots.stream().map(ValuationSnapshot::pbMedian).toList();
        List<BigDecimal> breakerHistory = snapshots.stream().map(ValuationSnapshot::netBreakerRatio).toList();

        BigDecimal pePercentile = latest == null ? null : Percentile.of(latest.peMedian(), peHistory);
        BigDecimal pbPercentile = latest == null ? null : Percentile.of(latest.pbMedian(), pbHistory);
        BigDecimal breakerPercentile = latest == null ? null : Percentile.of(latest.netBreakerRatio(), breakerHistory);

        BigDecimal erp = erp();
        BigDecimal erpPercentile = erpPercentile();

        List<ValuationOverviewView.IndexValuationView> indices = indices();

        BigDecimal thermometer = thermometer(pePercentile, erpPercentile, breakerPercentile);
        boolean accumulating = snapshots.size() < 5;

        return new ValuationOverviewView(latest, pePercentile, pbPercentile, breakerPercentile,
                erp, erpPercentile, thermometer, indices, accumulating);
    }

    public List<IndustryValuationView> industries(String sort) {
        LocalDate latest = repository.findLatestSnapshot() == null ? null : repository.findLatestSnapshot().tradingDay();
        if (latest == null) {
            return List.of();
        }
        List<IndustryValuationView> views = repository.findIndustryValuationsByDay(latest).stream()
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
        return new ValuationHistoryView(
                repository.findAllSnapshots(),
                repository.findAllTreasuryYields(),
                repository.findIndexValuations(HS300));
    }

    /** ERP = 沪深 300 股息率 − 10 年国债收益率；数据缺失返回 null。 */
    private BigDecimal erp() {
        IndexValuation hs300 = repository.findIndexValuations(HS300).stream()
                .filter(i -> i.dividendYield() != null)
                .max(Comparator.comparing(IndexValuation::tradingDay))
                .orElse(null);
        var treasury = repository.findAllTreasuryYields().stream()
                .max(Comparator.comparing(TreasuryYield::tradingDay))
                .orElse(null);
        if (hs300 == null || treasury == null) {
            return null;
        }
        return hs300.dividendYield().subtract(treasury.yield10y()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal erpPercentile() {
        BigDecimal erp = erp();
        if (erp == null) {
            return null;
        }
        List<BigDecimal> erpHistory = repository.findIndexValuations(HS300).stream()
                .map(IndexValuation::dividendYield)
                .toList();
        List<BigDecimal> treasuryHistory = repository.findAllTreasuryYields().stream()
                .map(TreasuryYield::yield10y)
                .toList();
        // 简化：以沪深 300 股息率分位近似 ERP 分位（ERP 与股息率同向，历史长度以国债序列为限）
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
                    List<BigDecimal> peHistory = history.stream().map(IndexValuation::pe).toList();
                    List<BigDecimal> pbHistory = history.stream().map(IndexValuation::pb).toList();
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
