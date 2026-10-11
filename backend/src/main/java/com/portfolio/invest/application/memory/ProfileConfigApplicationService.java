package com.portfolio.invest.application.memory;

import com.portfolio.invest.application.allocation.AllocationApplicationService;
import com.portfolio.invest.application.industry.IndustryWatchApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioOverviewView;
import com.portfolio.invest.application.screening.WatchlistApplicationService;
import com.portfolio.invest.application.screening.WatchlistItemView;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.memory.ProfileSectionRenderer;
import com.portfolio.invest.domain.memory.UserProfileConfig;
import com.portfolio.invest.domain.memory.UserProfileConfigRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 画像注入授权用例（F02，设计规格 §3.1/§3.5）：四开关读写（无行 = 全关，缺省 opt-in）
 * 与四段预览。
 *
 * <p>预览段值与注入侧（B5 {@code agent/ProfileContextBuilder}）共用
 * {@link ProfileSectionRenderer} 同一渲染路径——「所见即所注入」由同函数保证；预览不读开关
 * （disabled 段也出预览，让用户「先看再开」）。逐段独立 try/catch：单段数据源失败或为空 →
 * 该段 null（前端渲染「暂不可用」），不拖垮其余段（§3.2 边界；为空段与注入侧省略语义一致，
 * 不注入占位空值）。
 *
 * <p>数字来源纪律（§3.2）：持仓段各值直接取 {@link PortfolioOverviewView} 字段
 * {@code toPlainString()}，不二次计算——overview 无盈亏比字段，pnlRatio 恒传 null（省略括注）。
 */
@Service
public class ProfileConfigApplicationService {

    /** 预览 Map 四段键（§3.5 端点响应同名透传）。 */
    public static final String KEY_STYLE = "style";
    public static final String KEY_INDUSTRY = "industry";
    public static final String KEY_WATCHLIST = "watchlist";
    public static final String KEY_HOLDINGS = "holdings";

    private static final Logger log = LoggerFactory.getLogger(ProfileConfigApplicationService.class);

    private final UserProfileConfigRepository repository;
    private final AllocationApplicationService allocationService;
    private final IndustryWatchApplicationService industryWatchService;
    private final WatchlistApplicationService watchlistService;
    private final PortfolioApplicationService portfolioService;
    private final int watchlistLimit;

    public ProfileConfigApplicationService(
            UserProfileConfigRepository repository,
            AllocationApplicationService allocationService,
            IndustryWatchApplicationService industryWatchService,
            WatchlistApplicationService watchlistService,
            PortfolioApplicationService portfolioService,
            InvestProperties props) {
        this.repository = repository;
        this.allocationService = allocationService;
        this.industryWatchService = industryWatchService;
        this.watchlistService = watchlistService;
        this.portfolioService = portfolioService;
        // MS-31 B5：换注入 InvestProperties（getProfile().getWatchlistLimit()，缺省 30 同前）
        //——消除与 B5 注入侧 builder 的双机制同键漂移面（预览/注入同一配置源）
        this.watchlistLimit = props.getProfile().getWatchlistLimit();
    }

    /** 四开关；无配置行 = 全关。 */
    public ProfileConfigView config(Long userId) {
        return repository.findByUserId(userId)
                .map(c -> new ProfileConfigView(c.styleEnabled(), c.industryEnabled(),
                        c.watchlistEnabled(), c.holdingsEnabled()))
                .orElseGet(() -> new ProfileConfigView(false, false, false, false));
    }

    /** 全量保存四开关（upsert）：无行以 {@link UserProfileConfig#defaults} 起步。 */
    public void save(Long userId, boolean style, boolean industry, boolean watchlist, boolean holdings) {
        UserProfileConfig current = repository.findByUserId(userId)
                .orElseGet(() -> UserProfileConfig.defaults(userId));
        repository.save(current.update(style, industry, watchlist, holdings));
    }

    /** 四段预览（段值 = {@link ProfileSectionRenderer} 直出；失败/为空段 null）。 */
    public Map<String, String> preview(Long userId) {
        Map<String, String> preview = new LinkedHashMap<>();
        preview.put(KEY_STYLE, renderStyle(userId));
        preview.put(KEY_INDUSTRY, renderIndustry(userId));
        preview.put(KEY_WATCHLIST, renderWatchlist(userId));
        preview.put(KEY_HOLDINGS, renderHoldings(userId));
        return preview;
    }

    private String renderStyle(Long userId) {
        try {
            return allocationService.latestAssessment(userId)
                    .map(a -> ProfileSectionRenderer.riskProfile(a.profileName()))
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("画像预览风格段数据源失败（userId={}），该段置空", userId, e);
            return null;
        }
    }

    private String renderIndustry(Long userId) {
        try {
            List<String> codes = industryWatchService.listWatched(userId).stream()
                    .map(v -> v.industryCode())
                    .toList();
            return codes.isEmpty() ? null : ProfileSectionRenderer.watchedIndustries(codes);
        } catch (RuntimeException e) {
            log.warn("画像预览行业段数据源失败（userId={}），该段置空", userId, e);
            return null;
        }
    }

    private String renderWatchlist(Long userId) {
        try {
            List<WatchlistItemView> items = watchlistService.list(userId);
            if (items.isEmpty()) {
                return null;
            }
            List<String> entries = items.stream()
                    .map(i -> i.stockName() == null || i.stockName().isBlank()
                            ? i.stockCode() : i.stockCode() + " " + i.stockName())
                    .toList();
            return ProfileSectionRenderer.watchlist(entries, watchlistLimit);
        } catch (RuntimeException e) {
            log.warn("画像预览自选段数据源失败（userId={}），该段置空", userId, e);
            return null;
        }
    }

    private String renderHoldings(Long userId) {
        try {
            PortfolioOverviewView o = portfolioService.overview(userId);
            return ProfileSectionRenderer.holdings(o.totalAssets().toPlainString(),
                    o.totalPnl().toPlainString(), null, o.positionCount(),
                    o.cashTotal().toPlainString());
        } catch (RuntimeException e) {
            log.warn("画像预览持仓段数据源失败（userId={}），该段置空", userId, e);
            return null;
        }
    }

}
