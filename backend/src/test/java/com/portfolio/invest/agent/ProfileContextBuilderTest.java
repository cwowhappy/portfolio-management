package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.allocation.AssessmentView;
import com.portfolio.invest.application.industry.IndustryWatchApplicationService;
import com.portfolio.invest.application.industry.IndustryWatchView;
import com.portfolio.invest.application.memory.ProfileConfigApplicationService;
import com.portfolio.invest.application.memory.ProfileConfigView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioOverviewView;
import com.portfolio.invest.application.screening.WatchlistApplicationService;
import com.portfolio.invest.application.screening.WatchlistItemView;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.allocation.RiskProfile;
import com.portfolio.invest.domain.memory.ProfileSectionRenderer;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 画像注入块构建（MS-31 B5，设计规格 §3.2/§3.3）：四开关过滤 + 四段独立 try/catch +
 * 整块限长截断；授权全关/全无数据/整体异常 → {@code Optional.empty()}（对话构建绝不因画像
 * 失败中断）。
 *
 * <p>核心锚（Review Focus #4）：整块输出与 {@link ProfileSectionRenderer#wrap} 直出
 * <b>逐字符相等</b>——段拼装语义（自选条目「代码 名称」+ 空名回落裸代码、holdings 数字
 * toPlainString 直出、pnlRatio 恒 null）与 B4 预览侧字面量对齐，「所见即所注入」。
 */
class ProfileContextBuilderTest {

    private static final Long UID = 1L;
    private static final Instant T0 = Instant.parse("2026-10-11T08:00:00Z");
    private static final String TRUNCATION_NOTE = "（已截断）";

    private final ProfileConfigApplicationService configService = mock(ProfileConfigApplicationService.class);
    private final AllocationApplicationService allocation = mock(AllocationApplicationService.class);
    private final IndustryWatchApplicationService industryWatch = mock(IndustryWatchApplicationService.class);
    private final WatchlistApplicationService watchlist = mock(WatchlistApplicationService.class);
    private final PortfolioApplicationService portfolio = mock(PortfolioApplicationService.class);

    private ProfileContextBuilder builder() {
        return newBuilder(new InvestProperties());
    }

    private ProfileContextBuilder newBuilder(InvestProperties props) {
        return new ProfileContextBuilder(configService, allocation, industryWatch,
                watchlist, portfolio, props);
    }

    private void givenAllEnabled() {
        when(configService.config(UID)).thenReturn(new ProfileConfigView(true, true, true, true));
    }

    private void givenAllSourcesHealthy() {
        when(allocation.latestAssessment(UID)).thenReturn(Optional.of(
                new AssessmentView(28, RiskProfile.BALANCED, "平衡", List.of(), Map.of(), T0)));
        when(industryWatch.listWatched(UID)).thenReturn(List.of(
                new IndustryWatchView("801017", T0), new IndustryWatchView("801120", T0)));
        when(watchlist.list(UID)).thenReturn(List.of(
                item("600519", "贵州茅台"), item("000858", "五粮液")));
        when(portfolio.overview(UID)).thenReturn(new PortfolioOverviewView(
                new BigDecimal("1234567.89"), new BigDecimal("1222222.22"),
                new BigDecimal("12345"), new BigDecimal("0"),
                new BigDecimal("1000.50"), BigDecimal.ZERO, 8, 2));
    }

    private static WatchlistItemView item(String code, String name) {
        return new WatchlistItemView(code, name, null, null, null, null, null, null, T0);
    }

    @Test
    @DisplayName("全开+四源有数据：整块与 wrap(renderer 四段直出) 逐字符相等，自选条目串与 B4 预览字面量一致")
    void givenAllEnabledAndHealthy_whenBuild_thenCharEqualToRendererWrap() {
        givenAllEnabled();
        givenAllSourcesHealthy();

        Optional<String> block = builder().build(UID);

        String expected = ProfileSectionRenderer.wrap(List.of(
                ProfileSectionRenderer.riskProfile("平衡"),
                ProfileSectionRenderer.watchedIndustries(List.of("801017", "801120")),
                ProfileSectionRenderer.watchlist(List.of("600519 贵州茅台", "000858 五粮液"), 30),
                ProfileSectionRenderer.holdings("1234567.89", "12345", null, 8, "1000.50")));
        assertThat(block).contains(expected);
        // 拼装语义字面量锚（与 B4 ProfileConfigApplicationService 预览输出对齐）：
        // 自选条目 = 「代码 名称」，风格段 = 原值「平衡」渲染内追加「型」
        assertThat(block.orElseThrow()).contains("<risk_profile>平衡型</risk_profile>");
        assertThat(block.orElseThrow()).contains("<watchlist>600519 贵州茅台, 000858 五粮液</watchlist>");
        assertThat(block.orElseThrow()).contains(
                "<holdings_summary>总资产 ¥1234567.89，总盈亏 +¥12345，持仓 8 只，现金 ¥1000.50</holdings_summary>");
    }

    @Test
    @DisplayName("自选行快照名称空白/null：回落裸代码（逐字复刻 B4 预览拼装语义）")
    void givenBlankStockNames_whenBuild_thenBareCodeEntries() {
        givenAllEnabled();
        when(allocation.latestAssessment(UID)).thenReturn(Optional.empty());
        when(industryWatch.listWatched(UID)).thenReturn(List.of());
        when(watchlist.list(UID)).thenReturn(List.of(item("600519", null), item("000858", "   ")));
        when(portfolio.overview(UID)).thenThrow(new IllegalStateException("行情源不可用"));

        Optional<String> block = builder().build(UID);

        assertThat(block).contains(ProfileSectionRenderer.wrap(List.of(
                ProfileSectionRenderer.watchlist(List.of("600519", "000858"), 30))));
    }

    @Test
    @DisplayName("单数据源抛异常：该段省略 + WARN 留痕，其余段照常（逐段独立 try/catch）")
    void givenPortfolioSourceFailure_whenBuild_thenFailedSectionOmittedOthersIntact() {
        givenAllEnabled();
        givenAllSourcesHealthy();
        when(portfolio.overview(UID)).thenThrow(new IllegalStateException("行情源不可用"));

        Optional<String> block = builder().build(UID);

        assertThat(block).contains(ProfileSectionRenderer.wrap(List.of(
                ProfileSectionRenderer.riskProfile("平衡"),
                ProfileSectionRenderer.watchedIndustries(List.of("801017", "801120")),
                ProfileSectionRenderer.watchlist(List.of("600519 贵州茅台", "000858 五粮液"), 30))));
        assertThat(block.orElseThrow()).doesNotContain("<holdings_summary>");
    }

    @Test
    @DisplayName("disabled 段不取数不渲染：关两段只出余下段")
    void givenPartiallyEnabled_whenBuild_thenOnlyEnabledSectionsRendered() {
        when(configService.config(UID)).thenReturn(new ProfileConfigView(false, true, false, false));
        when(allocation.latestAssessment(UID)).thenReturn(Optional.of(
                new AssessmentView(28, RiskProfile.BALANCED, "平衡", List.of(), Map.of(), T0)));
        when(industryWatch.listWatched(UID)).thenReturn(List.of(new IndustryWatchView("801017", T0)));
        when(watchlist.list(UID)).thenReturn(List.of(item("600519", "贵州茅台")));
        when(portfolio.overview(UID)).thenReturn(new PortfolioOverviewView(
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, 1, 1));

        Optional<String> block = builder().build(UID);

        assertThat(block).contains(ProfileSectionRenderer.wrap(List.of(
                ProfileSectionRenderer.watchedIndustries(List.of("801017")))));
        assertThat(block.orElseThrow()).doesNotContain("<risk_profile>");
        assertThat(block.orElseThrow()).doesNotContain("<watchlist>");
        assertThat(block.orElseThrow()).doesNotContain("<holdings_summary>");
    }

    @Test
    @DisplayName("授权全关 → empty（sysPrompt 与现状逐字节一致的第一入口）")
    void givenAllDisabled_whenBuild_thenEmpty() {
        when(configService.config(UID)).thenReturn(new ProfileConfigView(false, false, false, false));

        assertThat(builder().build(UID)).isEmpty();
    }

    @Test
    @DisplayName("全开但全无数据（测评空/行业空/自选空/持仓源失败）→ empty，不注入占位空值")
    void givenEnabledButNoData_whenBuild_thenEmpty() {
        givenAllEnabled();
        when(allocation.latestAssessment(UID)).thenReturn(Optional.empty());
        when(industryWatch.listWatched(UID)).thenReturn(List.of());
        when(watchlist.list(UID)).thenReturn(List.of());
        when(portfolio.overview(UID)).thenThrow(new IllegalStateException("行情源不可用"));

        assertThat(builder().build(UID)).isEmpty();
    }

    @Test
    @DisplayName("整块超 block-max-chars：截断到阈值并附「（已截断）」尾注")
    void givenBlockBeyondMaxChars_whenBuild_thenTruncatedWithSuffix() {
        givenAllEnabled();
        givenAllSourcesHealthy();
        InvestProperties props = new InvestProperties();
        props.getProfile().setBlockMaxChars(50);

        Optional<String> block = newBuilder(props).build(UID);

        String full = ProfileSectionRenderer.wrap(List.of(
                ProfileSectionRenderer.riskProfile("平衡"),
                ProfileSectionRenderer.watchedIndustries(List.of("801017", "801120")),
                ProfileSectionRenderer.watchlist(List.of("600519 贵州茅台", "000858 五粮液"), 30),
                ProfileSectionRenderer.holdings("1234567.89", "12345", null, 8, "1000.50")));
        assertThat(full.length()).isGreaterThan(50);
        assertThat(block).contains(full.substring(0, 50) + TRUNCATION_NOTE);
        assertThat(block.orElseThrow()).startsWith("<user_profile>");
    }

    @Test
    @DisplayName("自选上限取 invest.profile.watchlist-limit（非 renderer 缺省 30）")
    void givenCustomWatchlistLimit_whenBuild_thenConfiguredLimitApplied() {
        givenAllEnabled();
        when(allocation.latestAssessment(UID)).thenReturn(Optional.empty());
        when(industryWatch.listWatched(UID)).thenReturn(List.of());
        when(portfolio.overview(UID)).thenThrow(new IllegalStateException("行情源不可用"));
        when(watchlist.list(UID)).thenReturn(List.of(
                item("600519", "贵州茅台"), item("000858", "五粮液"), item("601318", "中国平安")));
        InvestProperties props = new InvestProperties();
        props.getProfile().setWatchlistLimit(2);

        Optional<String> block = newBuilder(props).build(UID);

        assertThat(block).contains(ProfileSectionRenderer.wrap(List.of(
                ProfileSectionRenderer.watchlist(
                        List.of("600519 贵州茅台", "000858 五粮液", "601318 中国平安"), 2))));
    }

    @Test
    @DisplayName("builder 顶层异常（配置源失败）→ empty（对话构建绝不因画像失败中断红线）")
    void givenConfigSourceFailure_whenBuild_thenEmpty() {
        when(configService.config(UID)).thenThrow(new IllegalStateException("库不可用"));

        assertThat(builder().build(UID)).isEmpty();
    }

    @Test
    @DisplayName("版本资产模板骨架：包裹标签与禁传头注行和 renderer.wrap 输出逐字符一致，四段占位齐备")
    void givenTemplateAsset_whenCompareWithRendererWrap_thenEnvelopeCharIdentical() {
        String wrapped = ProfileSectionRenderer.wrap(
                List.of(ProfileSectionRenderer.riskProfile("平衡")));
        List<String> wrapLines = wrapped.lines().toList();
        List<String> templateLines = ProfileContextTemplate.TEXT.lines().toList();

        assertThat(templateLines).hasSize(7);
        assertThat(templateLines.get(0)).isEqualTo(wrapLines.get(0)); // <user_profile>
        assertThat(templateLines.get(1)).isEqualTo(wrapLines.get(1)); // 禁传头注逐字符一致
        assertThat(templateLines.get(templateLines.size() - 1))
                .isEqualTo(wrapLines.get(wrapLines.size() - 1)); // </user_profile>
        assertThat(templateLines.stream()
                .filter(line -> line.startsWith("<") && !line.contains("user_profile"))
                .map(line -> line.substring(0, line.indexOf('>') + 1))
                .toList())
                .containsExactly("<risk_profile>", "<watched_industries>",
                        "<watchlist>", "<holdings_summary>");
    }
}
