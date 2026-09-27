package com.portfolio.invest.application.alert;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原则预警评估器（纯函数，无副作用无状态）：启用中的规则 × 持仓 × 当日快照 → 违规清单。
 * 口径：仓位类分母 = Σ(close×quantity) 全部有价持仓（与 concentration 一致，不含现金）；
 * 行业分子 = 申万映射命中的持仓聚合（未映射票只进分母不产生行业违规）。
 * 比较 = BigDecimal.compareTo 严格大于（等于阈值不触发）。
 */
public final class PrincipleAlertEvaluator {

    public record Holding(String stockCode, String stockName, BigDecimal quantity) {}

    public List<AlertViolation> evaluate(List<PrincipleRule> rules, List<Holding> holdings,
                                         Map<String, StockMetric> metrics, Map<String, String> industries) {
        Map<String, BigDecimal> marketValues = new HashMap<>();
        BigDecimal total = BigDecimal.ZERO;
        for (Holding h : holdings) {
            StockMetric m = metrics.get(h.stockCode());
            if (m == null || m.close() == null) {
                continue; // 无当日收盘价：该票全维度跳过（fail-safe，不判罚）
            }
            BigDecimal value = m.close().multiply(h.quantity());
            marketValues.put(h.stockCode(), value);
            total = total.add(value);
        }
        List<AlertViolation> violations = new ArrayList<>();
        if (total.signum() <= 0) {
            return violations; // 无任何有价持仓：仓位类不判罚
        }
        for (PrincipleRule rule : rules) {
            if (!rule.enabled()) {
                continue;
            }
            switch (rule.metric()) {
                case SINGLE_POSITION_RATIO -> evaluateSinglePosition(rule, marketValues, total, metrics, violations);
                case INDUSTRY_POSITION_RATIO -> evaluateIndustry(rule, marketValues, total, industries, violations);
                case STOCK_PE_MAX -> evaluateValuation(rule, PrincipleMetric.STOCK_PE_MAX.label(), metrics,
                        StockMetric::peTtm, violations);
                case STOCK_PB_MAX -> evaluateValuation(rule, PrincipleMetric.STOCK_PB_MAX.label(), metrics,
                        StockMetric::pb, violations);
            }
        }
        return violations;
    }

    private void evaluateSinglePosition(PrincipleRule rule, Map<String, BigDecimal> values, BigDecimal total,
                                        Map<String, StockMetric> metrics, List<AlertViolation> out) {
        for (var e : values.entrySet()) {
            BigDecimal current = e.getValue().divide(total, 10, RoundingMode.HALF_UP);
            if (current.compareTo(rule.threshold()) > 0) {
                StockMetric m = metrics.get(e.getKey());
                out.add(new AlertViolation(rule.metric().label(), m.stockName() + "(" + e.getKey() + ")",
                        current, rule.threshold(), rule.description()));
            }
        }
    }

    private void evaluateIndustry(PrincipleRule rule, Map<String, BigDecimal> values, BigDecimal total,
                                  Map<String, String> industries, List<AlertViolation> out) {
        Map<String, BigDecimal> byIndustry = new HashMap<>();
        for (var e : values.entrySet()) {
            String industry = industries.get(e.getKey());
            if (industry == null) {
                continue; // 未映射：只进分母不产生行业违规
            }
            byIndustry.merge(industry, e.getValue(), BigDecimal::add);
        }
        for (var e : byIndustry.entrySet()) {
            BigDecimal current = e.getValue().divide(total, 10, RoundingMode.HALF_UP);
            if (current.compareTo(rule.threshold()) > 0) {
                out.add(new AlertViolation(rule.metric().label(), e.getKey(), current, rule.threshold(),
                        rule.description()));
            }
        }
    }

    private void evaluateValuation(PrincipleRule rule, String label, Map<String, StockMetric> metrics,
                                   java.util.function.Function<StockMetric, BigDecimal> extractor,
                                   List<AlertViolation> out) {
        for (StockMetric m : metrics.values()) {
            BigDecimal current = extractor.apply(m);
            if (current != null && current.compareTo(rule.threshold()) > 0) {
                out.add(new AlertViolation(label, m.stockName() + "(" + m.stockCode() + ")", current,
                        rule.threshold(), rule.description()));
            }
        }
    }
}
