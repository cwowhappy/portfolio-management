package com.portfolio.invest.agent;

import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.industry.IndustryWatchApplicationService;
import com.portfolio.invest.application.memory.ProfileConfigApplicationService;
import com.portfolio.invest.application.memory.ProfileConfigView;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioOverviewView;
import com.portfolio.invest.application.screening.WatchlistApplicationService;
import com.portfolio.invest.application.screening.WatchlistItemView;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.memory.ProfileSectionRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 画像注入块构建（MS-31 B5，设计规格 §3.2/§3.3）：按四开关取数、经
 * {@link ProfileSectionRenderer}（与 B4 预览同一渲染函数——「所见即所注入」）拼四段并
 * {@code wrap} 成 {@code <user_profile>} 整块，超 {@code invest.profile.block-max-chars}
 * 截断附「（已截断）」。
 *
 * <p>失败边界（需求 F02）：逐段独立 try/catch——单段数据源失败/为空该段省略 + WARN，
 * 不拖垮其余段；顶层 try/catch 全吞返 {@code Optional.empty()}——对话构建绝不因画像失败
 * 中断（沿 hook 异常直通先例），消费方 {@code HarnessAgentFactory.systemPrompt} 对 empty
 * 回落裸 {@code InvestSystemPrompt.TEXT}（与现状逐字节一致的回归红线）。
 *
 * <p>拼装纪律（Review Focus #4）：自选条目串「代码 名称」、空名回落裸代码——与 B4
 * {@code ProfileConfigApplicationService} 预览侧逐字同语义（该侧已验收不改引，字面量由
 * {@code ProfileContextBuilderTest} 双侧对齐断言锁定）；持仓数字直取
 * {@link PortfolioOverviewView} 字段 {@code toPlainString()}（overview 无盈亏比字段，
 * pnlRatio 恒 null，renderer 省略括注）。
 */
@Component
public class ProfileContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(ProfileContextBuilder.class);

    /** 整块截断尾注（§3.2 逐字）。 */
    static final String TRUNCATION_NOTE = "（已截断）";

    private final ProfileConfigApplicationService configService;
    private final AllocationApplicationService allocationService;
    private final IndustryWatchApplicationService industryWatchService;
    private final WatchlistApplicationService watchlistService;
    private final PortfolioApplicationService portfolioService;
    private final InvestProperties.Profile profile;

    public ProfileContextBuilder(ProfileConfigApplicationService configService,
                                 AllocationApplicationService allocationService,
                                 IndustryWatchApplicationService industryWatchService,
                                 WatchlistApplicationService watchlistService,
                                 PortfolioApplicationService portfolioService,
                                 InvestProperties props) {
        this.configService = configService;
        this.allocationService = allocationService;
        this.industryWatchService = industryWatchService;
        this.watchlistService = watchlistService;
        this.portfolioService = portfolioService;
        this.profile = props.getProfile();
    }

    /**
     * 构建画像注入整块（含 {@code <user_profile>} 包裹与禁传头注）。
     *
     * @return 授权全关 / 全无数据 / 构建整体失败 → {@code Optional.empty()}
     */
    public Optional<String> build(Long userId) {
        try {
            ProfileConfigView config = configService.config(userId);
            List<String> sections = new ArrayList<>();
            if (config.style()) {
                section(userId, "风格", () -> allocationService.latestAssessment(userId)
                        .map(a -> ProfileSectionRenderer.riskProfile(a.profileName())))
                        .ifPresent(sections::add);
            }
            if (config.industry()) {
                section(userId, "行业", () -> {
                    List<String> codes = industryWatchService.listWatched(userId).stream()
                            .map(v -> v.industryCode())
                            .toList();
                    return codes.isEmpty() ? Optional.empty()
                            : Optional.of(ProfileSectionRenderer.watchedIndustries(codes));
                }).ifPresent(sections::add);
            }
            if (config.watchlist()) {
                section(userId, "自选", () -> {
                    List<WatchlistItemView> items = watchlistService.list(userId);
                    return items.isEmpty() ? Optional.empty()
                            : Optional.of(ProfileSectionRenderer.watchlist(
                                    items.stream().map(ProfileContextBuilder::watchlistEntry).toList(),
                                    profile.getWatchlistLimit()));
                }).ifPresent(sections::add);
            }
            if (config.holdings()) {
                section(userId, "持仓", () -> Optional.of(renderHoldings(userId)))
                        .ifPresent(sections::add);
            }
            if (sections.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(truncate(ProfileSectionRenderer.wrap(sections)));
        } catch (RuntimeException e) { // 对话构建绝不因画像失败中断（红线）
            log.warn("画像块构建整体失败（userId={}），本轮不注入", userId, e);
            return Optional.empty();
        }
    }

    /** 单段独立 try/catch：数据源失败 → 该段省略 + WARN 留痕，不拖垮其余段。 */
    private Optional<String> section(Long userId, String name, Supplier<Optional<String>> render) {
        try {
            return render.get();
        } catch (RuntimeException e) {
            log.warn("画像注入{}段数据源失败（userId={}），该段省略", name, userId, e);
            return Optional.empty();
        }
    }

    /** 自选条目串：「代码 名称」；名称 null/空白回落裸代码（与 B4 预览拼装语义逐字一致）。 */
    private static String watchlistEntry(WatchlistItemView item) {
        return item.stockName() == null || item.stockName().isBlank()
                ? item.stockCode() : item.stockCode() + " " + item.stockName();
    }

    /** 持仓段：数字直取 overview 字段 toPlainString（pnlRatio 无源恒 null，§3.2 数字纪律）。 */
    private String renderHoldings(Long userId) {
        PortfolioOverviewView o = portfolioService.overview(userId);
        return ProfileSectionRenderer.holdings(o.totalAssets().toPlainString(),
                o.totalPnl().toPlainString(), null, o.positionCount(),
                o.cashTotal().toPlainString());
    }

    /** 整块限长：超 {@code block-max-chars} 截断到阈值并附「（已截断）」尾注。 */
    private String truncate(String block) {
        return block.length() <= profile.getBlockMaxChars()
                ? block : block.substring(0, profile.getBlockMaxChars()) + TRUNCATION_NOTE;
    }
}
