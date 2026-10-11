package com.portfolio.invest.application.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.allocation.AssessmentView;
import com.portfolio.invest.application.industry.IndustryWatchApplicationService;
import com.portfolio.invest.application.industry.IndustryWatchView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioOverviewView;
import com.portfolio.invest.application.screening.WatchlistApplicationService;
import com.portfolio.invest.application.screening.WatchlistItemView;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.allocation.RiskProfile;
import com.portfolio.invest.domain.memory.ProfileSectionRenderer;
import com.portfolio.invest.domain.memory.UserProfileConfig;
import com.portfolio.invest.domain.memory.UserProfileConfigRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 画像授权配置用例（MS-31 B4）：config 缺省全关 / save upsert 回读 / preview 四段。
 *
 * <p>核心锚（Review Focus #4）：preview 段值与 {@link ProfileSectionRenderer} 直出
 * <b>逐字符相等</b>——预览与注入共用同一渲染路径；单段数据源失败或为空 → 该段 null，
 * 其余段不受拖累（设计规格 §3.2 边界）。
 */
class ProfileConfigApplicationServiceTest {

    private static final Long UID = 1L;
    private static final Instant T0 = Instant.parse("2026-10-11T08:00:00Z");

    private final UserProfileConfigRepository repo = mock(UserProfileConfigRepository.class);
    private final AllocationApplicationService allocation = mock(AllocationApplicationService.class);
    private final IndustryWatchApplicationService industryWatch = mock(IndustryWatchApplicationService.class);
    private final WatchlistApplicationService watchlist = mock(WatchlistApplicationService.class);
    private final PortfolioApplicationService portfolio = mock(PortfolioApplicationService.class);

    private ProfileConfigApplicationService service;

    @BeforeEach
    void setUp() {
        service = newService(30);
    }

    private ProfileConfigApplicationService newService(int watchlistLimit) {
        InvestProperties props = new InvestProperties();
        props.getProfile().setWatchlistLimit(watchlistLimit);
        return new ProfileConfigApplicationService(
                repo, allocation, industryWatch, watchlist, portfolio, props);
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
    @DisplayName("config 无配置行返回四开关全关（决议 #3 缺省 opt-in）")
    void givenNoConfigRow_whenConfig_thenAllDisabled() {
        when(repo.findByUserId(UID)).thenReturn(Optional.empty());

        assertThat(service.config(UID))
                .isEqualTo(new ProfileConfigView(false, false, false, false));
    }

    @Test
    @DisplayName("config 有配置行时四开关如实回读")
    void givenConfigRow_whenConfig_thenFlagsReadBack() {
        when(repo.findByUserId(UID)).thenReturn(Optional.of(
                UserProfileConfig.defaults(UID).update(true, false, true, true)));

        assertThat(service.config(UID))
                .isEqualTo(new ProfileConfigView(true, false, true, true));
    }

    @Test
    @DisplayName("save 新用户：以 defaults 起步全量保存（不落占位行，仅 save 一次）")
    void givenNoRow_whenSave_thenUpsertFromDefaults() {
        when(repo.findByUserId(UID)).thenReturn(Optional.empty());

        service.save(UID, true, false, true, false);

        ArgumentCaptor<UserProfileConfig> captor = ArgumentCaptor.forClass(UserProfileConfig.class);
        verify(repo).save(captor.capture());
        UserProfileConfig saved = captor.getValue();
        assertThat(saved.userId()).isEqualTo(UID);
        assertThat(saved.styleEnabled()).isTrue();
        assertThat(saved.industryEnabled()).isFalse();
        assertThat(saved.watchlistEnabled()).isTrue();
        assertThat(saved.holdingsEnabled()).isFalse();
    }

    @Test
    @DisplayName("save 已有配置行：在既有行上覆盖四开关（开关态断言，不断言时刻）")
    void givenExistingRow_whenSave_thenUpdateInPlace() {
        UserProfileConfig existing = new UserProfileConfig(9L, UID, true, true, false, false, T0);
        when(repo.findByUserId(UID)).thenReturn(Optional.of(existing));

        service.save(UID, false, true, false, true);

        ArgumentCaptor<UserProfileConfig> captor = ArgumentCaptor.forClass(UserProfileConfig.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(9L);
        assertThat(captor.getValue().styleEnabled()).isFalse();
        assertThat(captor.getValue().industryEnabled()).isTrue();
        assertThat(captor.getValue().watchlistEnabled()).isFalse();
        assertThat(captor.getValue().holdingsEnabled()).isTrue();
    }

    @Test
    @DisplayName("preview 四段与 renderer 直出逐字符相等（同路径保证），键集恰为四段")
    void givenHealthySources_whenPreview_thenCharEqualToRendererOutput() {
        givenAllSourcesHealthy();

        Map<String, String> preview = service.preview(UID);

        assertThat(preview).containsOnlyKeys(
                ProfileConfigApplicationService.KEY_STYLE,
                ProfileConfigApplicationService.KEY_INDUSTRY,
                ProfileConfigApplicationService.KEY_WATCHLIST,
                ProfileConfigApplicationService.KEY_HOLDINGS);
        assertThat(preview.get(ProfileConfigApplicationService.KEY_STYLE))
                .isEqualTo(ProfileSectionRenderer.riskProfile("平衡"))
                .isEqualTo("<risk_profile>平衡型</risk_profile>");
        assertThat(preview.get(ProfileConfigApplicationService.KEY_INDUSTRY))
                .isEqualTo(ProfileSectionRenderer.watchedIndustries(List.of("801017", "801120")));
        assertThat(preview.get(ProfileConfigApplicationService.KEY_WATCHLIST))
                .isEqualTo(ProfileSectionRenderer.watchlist(
                        List.of("600519 贵州茅台", "000858 五粮液"), 30));
        assertThat(preview.get(ProfileConfigApplicationService.KEY_HOLDINGS))
                .isEqualTo(ProfileSectionRenderer.holdings(
                        "1234567.89", "12345", null, 8, "1000.50"));
    }

    @Test
    @DisplayName("preview 持仓数字直接取 overview 字段 toPlainString，盈亏比无源省略（§3.2 数字纪律）")
    void givenOverview_whenPreviewHoldings_thenFieldValuesVerbatimWithoutRatio() {
        givenAllSourcesHealthy();

        String holdings = service.preview(UID).get(ProfileConfigApplicationService.KEY_HOLDINGS);

        assertThat(holdings).doesNotContain("（"); // 无盈亏比括注（overview 无该字段，禁止二次计算）
        assertThat(holdings).isEqualTo("<holdings_summary>总资产 ¥1234567.89，"
                + "总盈亏 +¥12345，持仓 8 只，现金 ¥1000.50</holdings_summary>");
    }

    @Test
    @DisplayName("preview 自选超上限走 renderer 截断：前 M + 共 N 尾注")
    void givenWatchlistBeyondLimit_whenPreview_thenTruncatedByConfiguredLimit() {
        when(watchlist.list(UID)).thenReturn(List.of(
                item("600519", "贵州茅台"), item("000858", "五粮液"), item("601318", "中国平安")));

        String preview = newService(2).preview(UID).get(ProfileConfigApplicationService.KEY_WATCHLIST);

        assertThat(preview).isEqualTo(ProfileSectionRenderer.watchlist(
                List.of("600519 贵州茅台", "000858 五粮液", "601318 中国平安"), 2));
    }

    @Test
    @DisplayName("preview 自选行快照缺名称时回落裸代码")
    void givenWatchlistItemWithoutName_whenPreview_thenBareCode() {
        when(watchlist.list(UID)).thenReturn(List.of(item("600519", null)));

        String preview = service.preview(UID).get(ProfileConfigApplicationService.KEY_WATCHLIST);

        assertThat(preview)
                .isEqualTo(ProfileSectionRenderer.watchlist(List.of("600519"), 30));
    }

    @Test
    @DisplayName("preview 单数据源抛异常：该段 null，其余段照常渲染（逐段 try/catch）")
    void givenPortfolioSourceFailure_whenPreview_thenFailedSectionNullOthersIntact() {
        givenAllSourcesHealthy();
        when(portfolio.overview(UID)).thenThrow(new IllegalStateException("行情源不可用"));

        Map<String, String> preview = service.preview(UID);

        assertThat(preview.get(ProfileConfigApplicationService.KEY_HOLDINGS)).isNull();
        assertThat(preview.get(ProfileConfigApplicationService.KEY_STYLE))
                .isEqualTo(ProfileSectionRenderer.riskProfile("平衡"));
        assertThat(preview.get(ProfileConfigApplicationService.KEY_INDUSTRY))
                .isEqualTo(ProfileSectionRenderer.watchedIndustries(List.of("801017", "801120")));
        assertThat(preview.get(ProfileConfigApplicationService.KEY_WATCHLIST))
                .isEqualTo(ProfileSectionRenderer.watchlist(
                        List.of("600519 贵州茅台", "000858 五粮液"), 30));
    }

    @Test
    @DisplayName("preview 数据为空的段返回 null（与注入侧省略语义一致：不注入占位空值）")
    void givenEmptySources_whenPreview_thenEmptySectionsNull() {
        when(allocation.latestAssessment(UID)).thenReturn(Optional.empty());
        when(industryWatch.listWatched(UID)).thenReturn(List.of());
        when(watchlist.list(UID)).thenReturn(List.of());
        when(portfolio.overview(UID)).thenReturn(new PortfolioOverviewView(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0));

        Map<String, String> preview = service.preview(UID);

        assertThat(preview.get(ProfileConfigApplicationService.KEY_STYLE)).isNull();
        assertThat(preview.get(ProfileConfigApplicationService.KEY_INDUSTRY)).isNull();
        assertThat(preview.get(ProfileConfigApplicationService.KEY_WATCHLIST)).isNull();
        // overview 恒有聚合视图（零持仓也有汇总），持仓段照常渲染
        assertThat(preview.get(ProfileConfigApplicationService.KEY_HOLDINGS))
                .isEqualTo(ProfileSectionRenderer.holdings("0", "0", null, 0, "0"));
    }

    @Test
    @DisplayName("config 与 preview 互不干扰：disabled 段也出预览（先看再开，§3.5）")
    void givenAllDisabled_whenPreview_thenSectionsStillRendered() {
        givenAllSourcesHealthy();
        when(repo.findByUserId(UID)).thenReturn(Optional.empty());

        assertThat(service.config(UID))
                .isEqualTo(new ProfileConfigView(false, false, false, false));
        assertThat(service.preview(UID).get(ProfileConfigApplicationService.KEY_STYLE))
                .isEqualTo("<risk_profile>平衡型</risk_profile>");
    }
}
