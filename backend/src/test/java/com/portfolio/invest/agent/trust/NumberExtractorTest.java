package com.portfolio.invest.agent.trust;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 参数组②判定式单测（MS-29 B1，设计规格 §三/§4.2 步骤 1）：
 * 万/亿/万亿/百分比/千分位/小数提取与归一、四类排除项、NumberToken 字段与 occ 序次。
 */
class NumberExtractorTest {

    @DisplayName("万/亿/万亿提取与归一：1.9万亿→1.9e12、100亿→1e10、3000万→3e7，均为数据性")
    @Test
    void givenChineseUnitNumbers_whenExtract_thenNormalizedValues() {
        List<NumberToken> tokens =
                NumberExtractor.extract("贵州茅台市值1.9万亿，全年营收100亿，净利3000万");

        assertThat(tokens).extracting(NumberToken::snippet)
                .containsExactly("1.9万亿", "100亿", "3000万");
        assertThat(valueOf(tokens, "1.9万亿")).isEqualByComparingTo("1900000000000");
        assertThat(valueOf(tokens, "100亿")).isEqualByComparingTo("10000000000");
        assertThat(valueOf(tokens, "3000万")).isEqualByComparingTo("30000000");
        assertThat(tokens).allMatch(NumberToken::dataLike);
        assertThat(tokens).noneMatch(NumberToken::percent);
    }

    @DisplayName("万亿/亿同量级换算：1.9万亿 与 19000亿 归一值相等，snippet 不同则 occ 各自独立")
    @Test
    void givenTrillionVersusYi_whenExtract_thenSameMagnitude() {
        List<NumberToken> tokens = NumberExtractor.extract("市值1.9万亿，即19000亿");

        assertThat(valueOf(tokens, "1.9万亿"))
                .isEqualByComparingTo(valueOf(tokens, "19000亿"));
        assertThat(occOf(tokens, "1.9万亿")).isEqualTo(1);
        assertThat(occOf(tokens, "19000亿")).isEqualTo(1);
    }

    @DisplayName("百分比：% 不除 100 只标记；snippet 保留正负号原文，全角％同样识别")
    @Test
    void givenPercentNumbers_whenExtract_thenFlaggedWithoutDivision() {
        List<NumberToken> tokens = NumberExtractor.extract("涨幅+0.8%，同时跌幅-2.5％");

        NumberToken plus = bySnippet(tokens, "+0.8%");
        NumberToken minus = bySnippet(tokens, "-2.5％");
        assertThat(plus.value()).isEqualByComparingTo("0.8");
        assertThat(plus.percent()).isTrue();
        assertThat(minus.value()).isEqualByComparingTo("-2.5");
        assertThat(minus.percent()).isTrue();
        assertThat(plus.dataLike()).isTrue();
        assertThat(minus.dataLike()).isTrue();
    }

    @DisplayName("千分位与小数：3,400.55 去逗号归一；1520.33 元 snippet 含单位后缀")
    @Test
    void givenThousandSeparatorAndDecimal_whenExtract_thenCommaStripped() {
        List<NumberToken> tokens = NumberExtractor.extract("大盘报3,400.55，现价1520.33元");

        assertThat(bySnippet(tokens, "3,400.55").value()).isEqualByComparingTo("3400.55");
        assertThat(bySnippet(tokens, "3,400.55").dataLike()).isTrue();
        assertThat(bySnippet(tokens, "1520.33元").value()).isEqualByComparingTo("1520.33");
        assertThat(tokens).allMatch(NumberToken::dataLike);
    }

    @DisplayName("无单位纯小数：市盈率15.20 仍为数据性，snippet 保留尾零、值按数值比较")
    @Test
    void givenBareDecimal_whenExtract_thenDataLike() {
        List<NumberToken> tokens = NumberExtractor.extract("对应市盈率15.20，处于历史低位");

        NumberToken token = bySnippet(tokens, "15.20");
        assertThat(token.value()).isEqualByComparingTo("15.2");
        assertThat(token.dataLike()).isTrue();
        assertThat(token.percent()).isFalse();
    }

    @DisplayName("排除·纯年份：2026年 保留 token 但标记非数据性（无单位后缀、无小数点、1900~2100）")
    @Test
    void givenPureYear_whenExtract_thenKeptButNonData() {
        List<NumberToken> tokens = NumberExtractor.extract("回顾2026年的行情");

        assertThat(tokens).hasSize(1);
        NumberToken token = tokens.getFirst();
        assertThat(token.snippet()).isEqualTo("2026");
        assertThat(token.value()).isEqualByComparingTo("2026");
        assertThat(token.dataLike()).isFalse();
    }

    @DisplayName("排除·日期：10月5日、每月5号 均非数据性；同 snippet 的 occ 按文本出现顺序计数")
    @Test
    void givenDatePatterns_whenExtract_thenNonDataAndOccInTextOrder() {
        List<NumberToken> tokens = NumberExtractor.extract("10月5日收盘，每月5号复盘");

        assertThat(tokens).extracting(NumberToken::snippet).containsExactly("10", "5", "5");
        assertThat(tokens).noneMatch(NumberToken::dataLike);
        assertThat(occOf(tokens, "5")).isEqualTo(2);   // 最后一次出现为第 2 次
    }

    @DisplayName("排除·序号：第3 的紧前缀「第」判定非数据性")
    @Test
    void givenOrdinalPrefix_whenExtract_thenNonData() {
        List<NumberToken> tokens = NumberExtractor.extract("第3大重仓股");

        assertThat(tokens).hasSize(1);
        assertThat(tokens.getFirst().snippet()).isEqualTo("3");
        assertThat(tokens.getFirst().dataLike()).isFalse();
    }

    @DisplayName("排除·A股代码：独立 6 位纯数字（600519）非数据性；带单位后缀（600519股）不受代码排除")
    @Test
    void givenAShareCodeShape_whenExtract_thenExcludedOnlyForBareSixDigits() {
        List<NumberToken> withCode = NumberExtractor.extract("贵州茅台（600519）现价1520.33元");
        assertThat(bySnippet(withCode, "600519").dataLike()).isFalse();
        assertThat(bySnippet(withCode, "1520.33元").dataLike()).isTrue();

        List<NumberToken> withUnit = NumberExtractor.extract("本次增持600519股");
        assertThat(withUnit).hasSize(1);
        assertThat(withUnit.getFirst().snippet()).isEqualTo("600519股");
        assertThat(withUnit.getFirst().dataLike()).isTrue();
    }

    @DisplayName("白名单守卫：收于2026元——单位后缀在场则年份排除不适用（规则要求无单位后缀）")
    @Test
    void givenYearShapedPriceWithUnit_whenExtract_thenDataLike() {
        List<NumberToken> tokens = NumberExtractor.extract("该股当日收于2026元");

        assertThat(tokens).hasSize(1);
        assertThat(tokens.getFirst().snippet()).isEqualTo("2026元");
        assertThat(tokens.getFirst().dataLike()).isTrue();
    }

    @DisplayName("occ 序次：同 snippet 重复出现按 1-based 递增，不同 snippet 互不影响")
    @Test
    void givenRepeatedSnippet_whenExtract_thenOccIncrements() {
        List<NumberToken> tokens = NumberExtractor.extract("今日涨幅0.8%，本周累计涨幅0.8%，下周关注0.9%");

        assertThat(tokens).extracting(NumberToken::snippet)
                .containsExactly("0.8%", "0.8%", "0.9%");
        assertThat(occOf(tokens, "0.8%")).isEqualTo(2);   // 末次出现
        assertThat(occOf(tokens, "0.9%")).isEqualTo(1);
        assertThat(tokens).allMatch(NumberToken::dataLike);
    }

    @DisplayName("混合消息：年份/日期/代码排除，价格/百分比/市值锚定，顺序与原文一致")
    @Test
    void givenMixedMessage_whenExtract_thenJudgmentFormulaApplies() {
        List<NumberToken> tokens =
                NumberExtractor.extract("2026年10月5日，贵州茅台（600519）收于1520.33元，涨幅0.8%，市值1.9万亿");

        assertThat(tokens).extracting(NumberToken::snippet)
                .containsExactly("2026", "10", "5", "600519", "1520.33元", "0.8%", "1.9万亿");
        assertThat(bySnippet(tokens, "2026").dataLike()).isFalse();
        assertThat(bySnippet(tokens, "10").dataLike()).isFalse();
        assertThat(bySnippet(tokens, "5").dataLike()).isFalse();
        assertThat(bySnippet(tokens, "600519").dataLike()).isFalse();
        assertThat(bySnippet(tokens, "1520.33元").dataLike()).isTrue();
        assertThat(bySnippet(tokens, "0.8%").dataLike()).isTrue();
        assertThat(bySnippet(tokens, "1.9万亿").dataLike()).isTrue();
        assertThat(valueOf(tokens, "1.9万亿")).isEqualByComparingTo("1900000000000");
    }

    @DisplayName("护栏：null 与空串返回空列表（hook 宁可少标不可断流）")
    @Test
    void givenNullOrBlank_whenExtract_thenEmpty() {
        assertThat(NumberExtractor.extract(null)).isEmpty();
        assertThat(NumberExtractor.extract("")).isEmpty();
        assertThat(NumberExtractor.extract("纯文字无数字")).isEmpty();
    }

    private static NumberToken bySnippet(List<NumberToken> tokens, String snippet) {
        return tokens.stream()
                .filter(t -> snippet.equals(t.snippet()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到 snippet: " + snippet));
    }

    private static BigDecimal valueOf(List<NumberToken> tokens, String snippet) {
        return bySnippet(tokens, snippet).value();
    }

    private static int occOf(List<NumberToken> tokens, String snippet) {
        List<NumberToken> matches = tokens.stream()
                .filter(t -> snippet.equals(t.snippet())).toList();
        return matches.getLast().occ();
    }
}
