package com.portfolio.invest.domain.industry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ChainTier 枚举契约（设计规格 §三，照 FundingRoundTest 先例）：parse 接受枚举名
 * （wire/DB 同形态），非法值抛 IllegalArgumentException 供应用服务捕获转 INVALID_TIER；
 * label 为中文展示名；声明序即 上游→中游→下游 排序键（链组装 stages 排序用）。
 */
class ChainTierTest {

    @DisplayName("parse 合法值：三个枚举名大小写敏感往返")
    @Test
    void givenAllLegalTierNames_whenParse_thenRoundTrip() {
        assertThat(ChainTier.parse("UPSTREAM")).isEqualTo(ChainTier.UPSTREAM);
        assertThat(ChainTier.parse("MIDSTREAM")).isEqualTo(ChainTier.MIDSTREAM);
        assertThat(ChainTier.parse("DOWNSTREAM")).isEqualTo(ChainTier.DOWNSTREAM);
    }

    @DisplayName("parse 非法值：空串/小写/中文均抛 IAE（服务层捕获转行错误）")
    @Test
    void givenIllegalTierText_whenParse_thenThrowIllegalArgument() {
        assertThatThrownBy(() -> ChainTier.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainTier.parse("upstream")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChainTier.parse("上游")).isInstanceOf(IllegalArgumentException.class);
    }

    @DisplayName("label：中文展示名与枚举一一对应")
    @Test
    void givenEachTier_whenLabel_thenChineseName() {
        assertThat(ChainTier.UPSTREAM.label()).isEqualTo("上游");
        assertThat(ChainTier.MIDSTREAM.label()).isEqualTo("中游");
        assertThat(ChainTier.DOWNSTREAM.label()).isEqualTo("下游");
    }

    @DisplayName("声明序即 tier 序：上游 < 中游 < 下游（链组装 stages 排序键）")
    @Test
    void givenTierOrder_whenCompare_thenUpstreamMidstreamDownstream() {
        assertThat(ChainTier.UPSTREAM.ordinal()).isLessThan(ChainTier.MIDSTREAM.ordinal());
        assertThat(ChainTier.MIDSTREAM.ordinal()).isLessThan(ChainTier.DOWNSTREAM.ordinal());
    }
}
