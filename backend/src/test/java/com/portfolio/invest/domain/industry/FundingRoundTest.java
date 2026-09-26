package com.portfolio.invest.domain.industry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * FundingRound 枚举契约（设计规格 §三）：parse 接受 CSV 输入格式（下划线大写枚举名），
 * 非法值抛 IllegalArgumentException 供解析器 L2/L3 捕获转行错误；label 为中文展示名；
 * order() 为声明序即轮次序（全景卡轮次分布排序用）。
 */
class FundingRoundTest {

    @DisplayName("parse 合法值：全部 15 个枚举名大小写敏感往返")
    @Test
    void givenAllLegalRoundNames_whenParse_thenRoundTrip() {
        assertThat(FundingRound.parse("SEED")).isEqualTo(FundingRound.SEED);
        assertThat(FundingRound.parse("ANGEL")).isEqualTo(FundingRound.ANGEL);
        assertThat(FundingRound.parse("PRE_A")).isEqualTo(FundingRound.PRE_A);
        assertThat(FundingRound.parse("A")).isEqualTo(FundingRound.A);
        assertThat(FundingRound.parse("A_PLUS")).isEqualTo(FundingRound.A_PLUS);
        assertThat(FundingRound.parse("B")).isEqualTo(FundingRound.B);
        assertThat(FundingRound.parse("B_PLUS")).isEqualTo(FundingRound.B_PLUS);
        assertThat(FundingRound.parse("C")).isEqualTo(FundingRound.C);
        assertThat(FundingRound.parse("C_PLUS")).isEqualTo(FundingRound.C_PLUS);
        assertThat(FundingRound.parse("D")).isEqualTo(FundingRound.D);
        assertThat(FundingRound.parse("STRATEGIC")).isEqualTo(FundingRound.STRATEGIC);
        assertThat(FundingRound.parse("PRE_IPO")).isEqualTo(FundingRound.PRE_IPO);
        assertThat(FundingRound.parse("IPO")).isEqualTo(FundingRound.IPO);
        assertThat(FundingRound.parse("ACQUIRED")).isEqualTo(FundingRound.ACQUIRED);
        assertThat(FundingRound.parse("UNKNOWN")).isEqualTo(FundingRound.UNKNOWN);
    }

    @DisplayName("parse 非法值：空串/小写/中文/乱码均抛 IAE（解析器 L2 捕获转行错误）")
    @Test
    void givenIllegalRoundText_whenParse_thenThrowIllegalArgument() {
        assertThatThrownBy(() -> FundingRound.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FundingRound.parse("seed")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FundingRound.parse("A+")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FundingRound.parse("B轮")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FundingRound.parse("pre-a")).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("label：中文展示名与枚举一一对应")
    @Test
    void givenEachRound_whenLabel_thenChineseName() {
        assertThat(FundingRound.SEED.label()).isEqualTo("种子轮");
        assertThat(FundingRound.ANGEL.label()).isEqualTo("天使轮");
        assertThat(FundingRound.PRE_A.label()).isEqualTo("Pre-A轮");
        assertThat(FundingRound.A.label()).isEqualTo("A轮");
        assertThat(FundingRound.A_PLUS.label()).isEqualTo("A+轮");
        assertThat(FundingRound.B.label()).isEqualTo("B轮");
        assertThat(FundingRound.B_PLUS.label()).isEqualTo("B+轮");
        assertThat(FundingRound.C.label()).isEqualTo("C轮");
        assertThat(FundingRound.C_PLUS.label()).isEqualTo("C+轮");
        assertThat(FundingRound.D.label()).isEqualTo("D轮");
        assertThat(FundingRound.STRATEGIC.label()).isEqualTo("战略投资");
        assertThat(FundingRound.PRE_IPO.label()).isEqualTo("Pre-IPO轮");
        assertThat(FundingRound.IPO.label()).isEqualTo("IPO");
        assertThat(FundingRound.ACQUIRED.label()).isEqualTo("被收购");
        assertThat(FundingRound.UNKNOWN.label()).isEqualTo("未知");
    }

    @DisplayName("order：声明序即轮次序（种子轮最早，未知殿后）")
    @Test
    void givenRoundOrder_whenCompare_thenDeclarationOrderHolds() {
        assertThat(FundingRound.SEED.order()).isLessThan(FundingRound.ANGEL.order());
        assertThat(FundingRound.ANGEL.order()).isLessThan(FundingRound.PRE_A.order());
        assertThat(FundingRound.PRE_A.order()).isLessThan(FundingRound.A.order());
        assertThat(FundingRound.A.order()).isLessThan(FundingRound.A_PLUS.order());
        assertThat(FundingRound.A_PLUS.order()).isLessThan(FundingRound.B.order());
        assertThat(FundingRound.B.order()).isLessThan(FundingRound.B_PLUS.order());
        assertThat(FundingRound.B_PLUS.order()).isLessThan(FundingRound.C.order());
        assertThat(FundingRound.C.order()).isLessThan(FundingRound.C_PLUS.order());
        assertThat(FundingRound.C_PLUS.order()).isLessThan(FundingRound.D.order());
        assertThat(FundingRound.D.order()).isLessThan(FundingRound.STRATEGIC.order());
        assertThat(FundingRound.STRATEGIC.order()).isLessThan(FundingRound.PRE_IPO.order());
        assertThat(FundingRound.PRE_IPO.order()).isLessThan(FundingRound.IPO.order());
        assertThat(FundingRound.IPO.order()).isLessThan(FundingRound.ACQUIRED.order());
        assertThat(FundingRound.ACQUIRED.order()).isLessThan(FundingRound.UNKNOWN.order());
    }
}
