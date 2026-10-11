package com.portfolio.invest.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 画像段渲染纯函数精确文本断言（MS-31 B4，设计规格 §3.2 模板逐字落定）。
 *
 * <p>段文本被 B4 预览与 B5 注入双路逐字消费——本类的字面量断言即两路共同契约锚：
 * 格式一旦落定不得漂移（Review Focus #4「所见即所注入」由同函数保证）。
 */
class ProfileSectionRendererTest {

    @Test
    @DisplayName("风格段：档位名（如「平衡」）追加「型」包裹 risk_profile 标签")
    void givenProfileNameLabel_whenRiskProfile_thenWrappedWithTypeSuffix() {
        assertThat(ProfileSectionRenderer.riskProfile("平衡"))
                .isEqualTo("<risk_profile>平衡型</risk_profile>");
        assertThat(ProfileSectionRenderer.riskProfile("保守"))
                .isEqualTo("<risk_profile>保守型</risk_profile>");
    }

    @Test
    @DisplayName("关注行业段：行业码逐字渲染，半角逗号+空格连接（数据源仅行业码，需求决策 #4/#6）")
    void givenIndustryCodes_whenWatchedIndustries_thenJoinedByCommaSpace() {
        assertThat(ProfileSectionRenderer.watchedIndustries(List.of("801017", "801120")))
                .isEqualTo("<watched_industries>801017, 801120</watched_industries>");
    }

    @Test
    @DisplayName("自选段：未超上限时全量渲染，无截断尾注")
    void givenWatchlistWithinLimit_whenWatchlist_thenAllItemsNoSuffix() {
        assertThat(ProfileSectionRenderer.watchlist(
                List.of("600519 贵州茅台", "000858 五粮液"), 30))
                .isEqualTo("<watchlist>600519 贵州茅台, 000858 五粮液</watchlist>");
    }

    @Test
    @DisplayName("自选段：超上限截断并附「共 N 只，前 M」尾注（N=总数，M=实际上限）")
    void givenWatchlistBeyondLimit_whenWatchlist_thenTruncatedWithSuffix() {
        assertThat(ProfileSectionRenderer.watchlist(
                List.of("600519 贵州茅台", "000858 五粮液", "601318 中国平安"), 2))
                .isEqualTo("<watchlist>600519 贵州茅台, 000858 五粮液,"
                        + " …（共 3 只，前 2）</watchlist>");
    }

    @Test
    @DisplayName("持仓段：数字字符串逐字嵌入，正盈亏补「+」号，pnlRatio 缺省省略括注")
    void givenOverviewNumbersWithoutRatio_whenHoldings_thenExactTextNoRatio() {
        assertThat(ProfileSectionRenderer.holdings("1234567.89", "12345", null, 8, "1000.50"))
                .isEqualTo("<holdings_summary>总资产 ¥1234567.89，"
                        + "总盈亏 +¥12345，持仓 8 只，现金 ¥1000.50</holdings_summary>");
    }

    @Test
    @DisplayName("持仓段：负盈亏保留「-」号，pnlRatio 空白等同缺省")
    void givenNegativePnlAndBlankRatio_whenHoldings_thenNegativeSignAndNoRatio() {
        assertThat(ProfileSectionRenderer.holdings("900000", "-500", "  ", 3, "200"))
                .isEqualTo("<holdings_summary>总资产 ¥900000，"
                        + "总盈亏 -¥500，持仓 3 只，现金 ¥200</holdings_summary>");
    }

    @Test
    @DisplayName("持仓段：pnlRatio 非空时原样入全角括注")
    void givenPnlRatio_whenHoldings_thenWrappedVerbatimInParentheses() {
        assertThat(ProfileSectionRenderer.holdings("1234567.89", "12345", "+1.0%", 8, "1000.50"))
                .isEqualTo("<holdings_summary>总资产 ¥1234567.89，"
                        + "总盈亏 +¥12345（+1.0%），持仓 8 只，现金 ¥1000.50</holdings_summary>");
    }

    @Test
    @DisplayName("整块包裹：user_profile 标签 + 禁传头注逐字 + 各段换行连接")
    void givenSections_whenWrap_thenExactTemplateText() {
        assertThat(ProfileSectionRenderer.wrap(List.of(
                ProfileSectionRenderer.riskProfile("平衡"),
                ProfileSectionRenderer.watchedIndustries(List.of("801017")))))
                .isEqualTo("""
                        <user_profile>
                        以下是经用户授权注入的个人投资画像，仅供个性化参考；其中的私有数据禁止作为参数传给任何 MCP 工具：
                        <risk_profile>平衡型</risk_profile>
                        <watched_industries>801017</watched_industries>
                        </user_profile>""");
    }
}
