package com.portfolio.invest.application.alert;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PrincipleAlertEvaluatorTest {

    private final PrincipleAlertEvaluator evaluator = new PrincipleAlertEvaluator();

    private static PrincipleAlertEvaluator.Holding holding(String code, String name, String qty) {
        return new PrincipleAlertEvaluator.Holding(code, name, new BigDecimal(qty));
    }

    private static StockMetric metric(String code, String close, String pe, String pb) {
        return new StockMetric(code, code + "名", close == null ? null : new BigDecimal(close),
                pe == null ? null : new BigDecimal(pe), pb == null ? null : new BigDecimal(pb));
    }

    private static PrincipleRule rule(PrincipleMetric metric, String threshold) {
        // domain 工厂 create(userId, metric, threshold, enabled, description, now)——以 PrincipleRule.java 为准
        return PrincipleRule.create(1L, metric, new BigDecimal(threshold), true, "测试规则", Instant.now());
    }

    @Test
    @DisplayName("给定单票仓位超阈值，when评估，then产出一条violation")
    void given单票仓位超阈值_when评估_then一条violation() {
        var rules = List.of(rule(PrincipleMetric.SINGLE_POSITION_RATIO, "0.20"));
        var holdings = List.of(holding("600519", "贵州茅台", "100"), holding("000858", "五粮液", "100"),
                holding("601318", "中国平安", "100"), holding("600036", "招商银行", "100"),
                holding("601166", "兴业银行", "100"));
        // 茅台 250000 / 总 1000000 → 0.25 > 0.20；其余四票各 0.1875 不超阈值
        var metrics = Map.of("600519", metric("600519", "2500", null, null),
                "000858", metric("000858", "1875", null, null),
                "601318", metric("601318", "1875", null, null),
                "600036", metric("600036", "1875", null, null),
                "601166", metric("601166", "1875", null, null));
        var violations = evaluator.evaluate(rules, holdings, metrics, Map.of());
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).subject()).contains("600519");
        assertThat(violations.get(0).current()).isEqualByComparingTo("0.25");
        assertThat(violations.get(0).threshold()).isEqualByComparingTo("0.20");
    }

    @Test
    @DisplayName("给定当前值恰好等于阈值（scale不同），when评估，then不触发")
    void given等于阈值_when评估_then不触发() {
        var rules = List.of(rule(PrincipleMetric.SINGLE_POSITION_RATIO, "0.2000"));
        var holdings = List.of(holding("600519", "贵州茅台", "100"), holding("000858", "五粮液", "100"),
                holding("601318", "中国平安", "100"), holding("600036", "招商银行", "100"),
                holding("601166", "兴业银行", "100"));
        // 等权五票：每票 200000 / 1000000 = 0.2000 == 阈值 0.2000（scale 不同）→ 严格大于不触发
        var metrics = Map.of("600519", metric("600519", "2000", null, null),
                "000858", metric("000858", "2000", null, null),
                "601318", metric("601318", "2000", null, null),
                "600036", metric("600036", "2000", null, null),
                "601166", metric("601166", "2000", null, null));
        assertThat(evaluator.evaluate(rules, holdings, metrics, Map.of())).isEmpty();
    }

    @Test
    @DisplayName("给定同行业两票聚合超阈值，when评估，then行业violation且subject为行业名")
    void given行业聚合超阈值_when评估_then行业violation() {
        var rules = List.of(rule(PrincipleMetric.INDUSTRY_POSITION_RATIO, "0.20"));
        var holdings = List.of(holding("600519", "贵州茅台", "100"), holding("000858", "五粮液", "100"),
                holding("601318", "中国平安", "100"));
        var metrics = Map.of("600519", metric("600519", "1200", null, null),
                "000858", metric("000858", "1100", null, null),
                "601318", metric("601318", "7700", null, null)); // 白酒 0.23 > 0.20
        var violations = evaluator.evaluate(rules, holdings, metrics,
                Map.of("600519", "白酒", "000858", "白酒"));
        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).subject()).isEqualTo("白酒");
        assertThat(violations.get(0).current()).isEqualByComparingTo("0.23");
    }

    @Test
    @DisplayName("给定PE与PB均超阈值，when评估，then两条violation")
    void givenPE与PB超阈_when评估_then两条violation() {
        var rules = List.of(rule(PrincipleMetric.STOCK_PE_MAX, "40"), rule(PrincipleMetric.STOCK_PB_MAX, "8"));
        var holdings = List.of(holding("600519", "贵州茅台", "100"));
        var metrics = Map.of("600519", metric("600519", "2000", "45.5", "9.2"));
        var violations = evaluator.evaluate(rules, holdings, metrics, Map.of());
        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(AlertViolation::metricLabel)
                .containsExactlyInAnyOrder("个股PE上限", "个股PB上限");
    }

    @Test
    @DisplayName("给定PE为null与close为null，when评估，then对应维度跳过且其余正常")
    void given快照字段缺失_when评估_then跳过该维度() {
        var rules = List.of(rule(PrincipleMetric.STOCK_PE_MAX, "40"),
                rule(PrincipleMetric.SINGLE_POSITION_RATIO, "0.20"));
        var holdings = List.of(holding("600519", "贵州茅台", "100"), holding("000858", "五粮液", "100"),
                holding("601318", "中国平安", "100"), holding("600036", "招商银行", "100"),
                holding("601166", "兴业银行", "100"), holding("300750", "宁德时代", "100"),
                holding("002594", "比亚迪", "100"));
        // 茅台 close 缺 → 市值跳过（不进分母）；其余六票各 500000/3000000 ≈ 0.167 < 0.20 不违规；PE 全缺 → PE 维度跳过
        var metrics = Map.of("600519", metric("600519", null, null, null),
                "000858", metric("000858", "5000", null, null),
                "601318", metric("601318", "5000", null, null),
                "600036", metric("600036", "5000", null, null),
                "601166", metric("601166", "5000", null, null),
                "300750", metric("300750", "5000", null, null),
                "002594", metric("002594", "5000", null, null));
        assertThat(evaluator.evaluate(rules, holdings, metrics, Map.of())).isEmpty();
    }

    @Test
    @DisplayName("给定全部close缺失，when评估，then空列表不判罚")
    void given全部无close_when评估_then空列表() {
        var rules = List.of(rule(PrincipleMetric.SINGLE_POSITION_RATIO, "0.01"));
        var holdings = List.of(holding("600519", "贵州茅台", "100"));
        var metrics = Map.of("600519", metric("600519", null, null, null));
        assertThat(evaluator.evaluate(rules, holdings, metrics, Map.of())).isEmpty();
    }
}
