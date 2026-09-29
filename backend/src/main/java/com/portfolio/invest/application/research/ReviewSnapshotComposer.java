package com.portfolio.invest.application.research;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.analytics.AnalyticsApplicationService;
import com.portfolio.invest.application.analytics.NavSeriesView;
import com.portfolio.invest.domain.analytics.StockClosePort;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import com.portfolio.invest.domain.research.EntryBatch;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchProject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 复盘快照组装器（M16-F14，D11）：创建复盘时<b>写入定格</b>（不复算历史）——
 * 区间收益重算自 {@link AnalyticsApplicationService#nav(Long)} 净值序列切窗
 * （跨域消费经 application，NFR-4），每笔圈内 trade 对照其「后续走势」max/minClose
 * （收盘序列取 {@link StockClosePort}，stock_valuation_daily.close——与
 * {@link MarketSnapshotAssembler} Ruling-17 双文案源同源，口径文案沿用「东财收盘」）。
 *
 * <p>归因圈选（D11）：时间窗 = 各批次建仓期间 ∪ [from,to] 持有期。批次无日期字段，
 * 建仓期间按「该批次价格带 [priceLow, priceHigh] 内的 BUY 成交」反推 min/max 日期；
 * 圈内 trade = 日期落在任一窗口内——同标的他项目/早期窗外交易不并入（Review Focus 2），
 * trade_ids 去重升序（Review Focus 5）。无数据字段一律字符串「无数据」，不留 null/0
 * （Review Focus 1）。「后续走势」截点经注入 Clock 定（照 {@link MarketSnapshotAssembler}
 * 双构造器先例）。
 */
@Service
public class ReviewSnapshotComposer {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 无数据标注（Review Focus 1：字符串而非 null/0，手算一致性验收的易错点）。 */
    private static final String NO_DATA = "无数据";
    /** 价格口径（Ruling-17 双文案源的仅 close 分支——快照只消费收盘序列）。 */
    private static final String PRICE_BASIS = "东财收盘";
    /** 净值与区间收益口径：序列来源 + 收益公式（自动计算与手算一致的验收说明）。 */
    private static final String NAV_BASIS = "组合 totalValue 日序列（analytics 重放）；periodReturn=区间末/首−1";
    /** 区间收益除法 scale（仓库 BigDecimal 惯例：显式 scale + HALF_UP）。 */
    private static final int RETURN_SCALE = 6;

    private final AnalyticsApplicationService analytics;
    private final PortfolioRepository portfolioRepository;
    private final StockClosePort stockClose;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 主构造器（@Autowired：存在测试专用重载构造器时需显式指定注入入口，照 MarketSnapshotAssembler 先例）。 */
    @Autowired
    public ReviewSnapshotComposer(AnalyticsApplicationService analytics,
                                  PortfolioRepository portfolioRepository,
                                  StockClosePort stockClose, ObjectMapper mapper) {
        this(analytics, portfolioRepository, stockClose, mapper, Clock.system(ZONE));
    }

    /** 测试注入：固定时钟（「后续走势」截点确定性）。 */
    ReviewSnapshotComposer(AnalyticsApplicationService analytics, PortfolioRepository portfolioRepository,
                           StockClosePort stockClose, ObjectMapper mapper, Clock clock) {
        this.analytics = analytics;
        this.portfolioRepository = portfolioRepository;
        this.stockClose = stockClose;
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 组装复盘快照 JSON：区间/截点/价格与净值口径标注 + navSeries（切窗）+ 区间收益
     * （重算）+ 圈内 trade 明细（{date, price, side} vs 其后 maxClose/minClose）+
     * trade_ids（去重排序）+ 归因时间窗（口径留痕）。
     */
    public String compose(Long userId, ResearchProject project, LocalDate from, LocalDate to,
                          List<EntryBatch> batches) {
        if (userId == null) {
            throw new ResearchException(ResearchErrorCode.USER_REQUIRED, "归属用户不能为空");
        }
        if (project == null) {
            throw new ResearchException(ResearchErrorCode.PROJECT_REQUIRED, "归属项目不能为空");
        }
        if (from == null || to == null || from.isAfter(to)) {
            throw new ResearchException(ResearchErrorCode.REVIEW_PERIOD_INVALID,
                    "复盘区间起止不能为空且起点不能晚于终点");
        }
        LocalDate asOf = LocalDate.now(clock);
        List<EntryBatch> plan = batches == null ? List.of() : batches;
        List<Trade> stockTrades = stockTrades(userId, project.stockCode());
        List<Window> windows = windows(stockTrades, plan, from, to);
        List<Trade> inCircle = inCircle(stockTrades, windows);

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("periodStart", from.toString());
        root.put("periodEnd", to.toString());
        root.put("asOf", asOf.toString());
        root.put("priceBasis", PRICE_BASIS);
        root.put("navBasis", NAV_BASIS);
        putNav(root, userId, from, to);
        root.put("trades", tradesSection(project.stockCode(), inCircle, asOf));
        root.put("tradeIds", inCircle.stream().map(Trade::id).filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new)).stream().toList());
        root.put("attributionWindow", windows.stream().map(w -> {
            Map<String, Object> win = new LinkedHashMap<>();
            win.put("start", w.start().toString());
            win.put("end", w.end().toString());
            return win;
        }).toList());
        try {
            return mapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("复盘快照序列化失败：" + project.id(), e);
        }
    }

    /** navSeries 切窗 + 区间收益重算：窗内 <2 点或首点 ≤0 → 「无数据」。 */
    private void putNav(Map<String, Object> root, Long userId, LocalDate from, LocalDate to) {
        List<NavSeriesView.NavPoint> inWindow = analytics.nav(userId).stream()
                .flatMap(v -> v.points().stream())
                .filter(p -> !p.date().isBefore(from) && !p.date().isAfter(to))
                .toList();
        if (inWindow.isEmpty()) {
            root.put("navSeries", NO_DATA);
            root.put("periodReturn", NO_DATA);
            return;
        }
        root.put("navSeries", inWindow.stream().map(p -> {
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("date", p.date().toString());
            point.put("value", p.totalValue());
            return point;
        }).toList());
        NavSeriesView.NavPoint first = inWindow.get(0);
        NavSeriesView.NavPoint last = inWindow.get(inWindow.size() - 1);
        if (inWindow.size() < 2 || first.totalValue().signum() <= 0) {
            root.put("periodReturn", NO_DATA);
            return;
        }
        root.put("periodReturn", last.totalValue()
                .divide(first.totalValue(), RETURN_SCALE, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE));
    }

    /** 圈内 trade 明细：{id, date, price, side} vs 其后 (date, asOf] maxClose/minClose；无后续收盘 → 「无数据」。 */
    private Object tradesSection(String stockCode, List<Trade> inCircle, LocalDate asOf) {
        if (inCircle.isEmpty()) {
            return NO_DATA;
        }
        NavigableMap<LocalDate, BigDecimal> closes = new TreeMap<>(stockClose.closes(stockCode,
                inCircle.get(0).tradeDate(), asOf));
        List<Object> out = new ArrayList<>(inCircle.size());
        for (Trade trade : inCircle) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", trade.id());
            item.put("date", trade.tradeDate().toString());
            item.put("price", trade.price());
            item.put("side", trade.type().name());
            putAfterMove(item, closes.tailMap(trade.tradeDate(), false));
            out.add(item);
        }
        return out;
    }

    private void putAfterMove(Map<String, Object> item, NavigableMap<LocalDate, BigDecimal> after) {
        if (after.isEmpty()) {
            item.put("afterMaxClose", NO_DATA);
            item.put("afterMinClose", NO_DATA);
            return;
        }
        BigDecimal max = null;
        BigDecimal min = null;
        for (BigDecimal close : after.values()) {
            if (max == null || close.compareTo(max) > 0) {
                max = close;
            }
            if (min == null || close.compareTo(min) < 0) {
                min = close;
            }
        }
        item.put("afterMaxClose", max);
        item.put("afterMinClose", min);
    }

    /** 按标的取全部交易：组合 → 持仓滤 stockCode → 逐持仓流水（D11 同标的多项目共用流水池）。 */
    private List<Trade> stockTrades(Long userId, String stockCode) {
        return portfolioRepository.findPortfolioByUserId(userId)
                .map(portfolio -> portfolioRepository.findPositionsByPortfolioId(portfolio.id()).stream()
                        .filter(pos -> stockCode.equals(pos.stockCode()))
                        .flatMap(pos -> portfolioRepository.findTradesByPositionId(pos.id()).stream())
                        .toList())
                .orElse(List.of());
    }

    /**
     * 归因时间窗（D11）：各批次建仓期间（批次价格带内 BUY 成交的 min/max 日期——批次无
     * 日期字段，成交反推）+ [from,to] 持有期；无成交批次不贡献窗口。
     */
    private List<Window> windows(List<Trade> trades, List<EntryBatch> batches, LocalDate from, LocalDate to) {
        List<Window> out = new ArrayList<>();
        for (EntryBatch batch : batches) {
            LocalDate min = null;
            LocalDate max = null;
            for (Trade trade : trades) {
                if (trade.type() == TradeType.BUY
                        && trade.price().compareTo(batch.priceLow()) >= 0
                        && trade.price().compareTo(batch.priceHigh()) <= 0) {
                    if (min == null || trade.tradeDate().isBefore(min)) {
                        min = trade.tradeDate();
                    }
                    if (max == null || trade.tradeDate().isAfter(max)) {
                        max = trade.tradeDate();
                    }
                }
            }
            if (min != null) {
                out.add(new Window(min, max));
            }
        }
        out.add(new Window(from, to));
        return out;
    }

    /** 圈内 trade：日期落在任一窗口内（窗外不并入），按（日期, id）稳定序输出。 */
    private List<Trade> inCircle(List<Trade> trades, List<Window> windows) {
        return trades.stream()
                .filter(t -> windows.stream().anyMatch(w -> w.covers(t.tradeDate())))
                .sorted(Comparator.comparing(Trade::tradeDate)
                        .thenComparing(Trade::id, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }

    /** 归因时间窗区间（口径留痕写入快照）。 */
    private record Window(LocalDate start, LocalDate end) {
        boolean covers(LocalDate day) {
            return !day.isBefore(start) && !day.isAfter(end);
        }
    }
}
