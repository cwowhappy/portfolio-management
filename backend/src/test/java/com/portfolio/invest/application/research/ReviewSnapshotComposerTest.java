package com.portfolio.invest.application.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.analytics.AnalyticsApplicationService;
import com.portfolio.invest.application.analytics.NavSeriesView;
import com.portfolio.invest.domain.analytics.StockClosePort;
import com.portfolio.invest.domain.portfolio.CostMethod;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.portfolio.TradeType;
import com.portfolio.invest.domain.research.EntryBatch;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchStage;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 复盘快照组装切片（F14 写入定格 + D11 归因圈选）：mock analytics / 收盘端口 / 持仓仓库，
 * 固定时钟定「后续走势」截点；断言口径字段、「无数据」标注（Focus 1）、
 * 窗外 trade 排除（Focus 2）、trade_ids 去重排序（Focus 5）。
 */
class ReviewSnapshotComposerTest {

    private static final Instant NOW = Instant.parse("2026-09-28T01:00:00Z");
    /** 固定时钟（Asia/Shanghai 2026-09-28）：「后续走势」截点确定性。 */
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 28);
    private static final LocalDate FROM = LocalDate.of(2026, 2, 1);
    private static final LocalDate TO = LocalDate.of(2026, 2, 28);
    private static final String CODE = "600519";

    private final AnalyticsApplicationService analytics = mock(AnalyticsApplicationService.class);
    private final PortfolioRepository portfolioRepository = mock(PortfolioRepository.class);
    private final StockClosePort stockClose = mock(StockClosePort.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ReviewSnapshotComposer composer;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Shanghai"));
        composer = new ReviewSnapshotComposer(analytics, portfolioRepository, stockClose, mapper, clock);
    }

    // ---- 夹具 ----

    private static ResearchProject project() {
        return ResearchProject.reconstitute(5L, 9L, CODE, "贵州茅台", "801120",
                "茅台扩产研究", ResearchStage.REVIEW, ProjectStatus.ACTIVE, true, 0L, NOW, NOW);
    }

    /** 同标的两持仓（id 11/22）：trade 跨 position 归并。 */
    private void stubPortfolio() {
        when(portfolioRepository.findPortfolioByUserId(9L))
                .thenReturn(Optional.of(Portfolio.reconstitute(2L, 9L, CostMethod.WEIGHTED_AVG, NOW, NOW)));
        when(portfolioRepository.findPositionsByPortfolioId(2L)).thenReturn(List.of(
                Position.reconstitute(11L, 2L, 3L, CODE, "贵州茅台", new BigDecimal("200"),
                        new BigDecimal("10"), new BigDecimal("10"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, NOW, NOW),
                Position.reconstitute(22L, 2L, 3L, CODE, "贵州茅台", new BigDecimal("100"),
                        new BigDecimal("12"), new BigDecimal("12"), BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, NOW, NOW)));
    }

    private static Trade trade(long id, long positionId, TradeType type, String date, String price) {
        return new Trade(id, positionId, type, LocalDate.parse(date), new BigDecimal(price),
                BigDecimal.TEN, BigDecimal.ZERO, NOW);
    }

    private static NavSeriesView nav(String... dateValuePairs) {
        List<NavSeriesView.NavPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < dateValuePairs.length; i += 2) {
            points.add(new NavSeriesView.NavPoint(LocalDate.parse(dateValuePairs[i]),
                    new BigDecimal(dateValuePairs[i + 1])));
        }
        return new NavSeriesView(points.get(0).date(), points.get(points.size() - 1).date(),
                points, java.util.Map.of());
    }

    private static SortedMap<LocalDate, BigDecimal> closes(String... dateValuePairs) {
        SortedMap<LocalDate, BigDecimal> out = new TreeMap<>();
        for (int i = 0; i < dateValuePairs.length; i += 2) {
            out.put(LocalDate.parse(dateValuePairs[i]), new BigDecimal(dateValuePairs[i + 1]));
        }
        return out;
    }

    // ---- 主链路：口径标注 + 区间收益重算 + 圈内 trade vs 后续走势 ----

    @DisplayName("全景：nav 切窗重算区间收益、圈内 trade 带 max/minClose、口径字段齐备（priceBasis=东财收盘）")
    @Test
    void givenNavTradesAndCloses_whenCompose_thenJsonContainsBasisAndComputedFields() throws Exception {
        // nav 全序列：窗前 1 点 + 窗内 3 点 + 窗后 1 点（切窗后只留窗内）
        when(analytics.nav(9L)).thenReturn(Optional.of(nav(
                "2026-01-15", "1000", "2026-02-01", "1100",
                "2026-02-10", "1210", "2026-02-28", "1155", "2026-03-05", "1200")));
        stubPortfolio();
        // 批次价格带 [10,11]：T1/T2 买入价在带内 → 建仓期间 [01-10, 01-20]
        // T3 卖出在持有期 [from,to] 内；T4 窗外（早于建仓期、价在带外）→ 另一项目交易
        when(portfolioRepository.findTradesByPositionId(11L)).thenReturn(List.of(
                trade(104, 11L, TradeType.BUY, "2025-12-05", "50.00"),
                trade(101, 11L, TradeType.BUY, "2026-01-10", "10.50"),
                trade(102, 11L, TradeType.BUY, "2026-01-20", "10.80")));
        when(portfolioRepository.findTradesByPositionId(22L)).thenReturn(List.of(
                trade(103, 22L, TradeType.SELL, "2026-02-15", "12.00")));
        when(stockClose.closes(eq(CODE), any(LocalDate.class), eq(AS_OF))).thenReturn(closes(
                "2026-01-12", "11.20", "2026-01-25", "9.80",
                "2026-02-16", "12.30", "2026-03-02", "10.90"));

        String json = composer.compose(9L, project(), FROM, TO,
                List.of(new EntryBatch(1, new BigDecimal("10"), new BigDecimal("11"), 100, null,
                        new BigDecimal("0.5"))));

        JsonNode root = mapper.readTree(json);
        // 区间与口径标注（priceBasis 复用 Ruling-17 双文案源的仅 close 分支）
        assertThat(root.path("periodStart").asText()).isEqualTo("2026-02-01");
        assertThat(root.path("periodEnd").asText()).isEqualTo("2026-02-28");
        assertThat(root.path("asOf").asText()).isEqualTo("2026-09-28");
        assertThat(root.path("priceBasis").asText()).isEqualTo("东财收盘");
        assertThat(root.path("navBasis").asText()).isNotBlank();
        // navSeries 切窗：只留 [02-01, 02-28] 内 3 点
        JsonNode navSeries = root.path("navSeries");
        assertThat(navSeries.isArray()).isTrue();
        assertThat(navSeries).hasSize(3);
        assertThat(navSeries.get(0).path("date").asText()).isEqualTo("2026-02-01");
        assertThat(navSeries.get(2).path("date").asText()).isEqualTo("2026-02-28");
        // 区间收益重算自 nav：1155/1100 − 1 = 0.05
        assertThat(root.path("periodReturn").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.05"));
        // 圈内 3 笔（T4 窗外排除，Focus 2），每笔 {date, price, side} vs 其后 max/minClose
        JsonNode trades = root.path("trades");
        assertThat(trades.isArray()).isTrue();
        assertThat(trades).hasSize(3);
        assertThat(trades.get(0).path("id").asLong()).isEqualTo(101L);
        assertThat(trades.get(0).path("date").asText()).isEqualTo("2026-01-10");
        assertThat(trades.get(0).path("price").decimalValue()).isEqualByComparingTo("10.50");
        assertThat(trades.get(0).path("side").asText()).isEqualTo("BUY");
        assertThat(trades.get(0).path("afterMaxClose").decimalValue()).isEqualByComparingTo("12.30");
        assertThat(trades.get(0).path("afterMinClose").decimalValue()).isEqualByComparingTo("9.80");
        assertThat(trades.get(2).path("id").asLong()).isEqualTo(103L);
        assertThat(trades.get(2).path("side").asText()).isEqualTo("SELL");
        assertThat(trades.get(2).path("afterMaxClose").decimalValue()).isEqualByComparingTo("12.30");
        assertThat(trades.get(2).path("afterMinClose").decimalValue()).isEqualByComparingTo("10.90");
        // trade_ids 去重排序（Focus 5）：窗外 104 不在列
        StringBuilder ids = new StringBuilder();
        root.path("tradeIds").forEach(n -> ids.append(n.asLong()).append(","));
        assertThat(ids.toString()).isEqualTo("101,102,103,");
        // 归因时间窗口径：批次建仓期间 + 持有期
        JsonNode windows = root.path("attributionWindow");
        assertThat(windows).hasSize(2);
        assertThat(windows.get(0).path("start").asText()).isEqualTo("2026-01-10");
        assertThat(windows.get(0).path("end").asText()).isEqualTo("2026-01-20");
        assertThat(windows.get(1).path("start").asText()).isEqualTo("2026-02-01");
        assertThat(windows.get(1).path("end").asText()).isEqualTo("2026-02-28");
    }

    // ---- Focus 1：无 nav →「无数据」非 null/0 ----

    @DisplayName("区间无 nav：navSeries/periodReturn 为字符串「无数据」，非 null 非 0（Focus 1）")
    @Test
    void givenNoNav_whenCompose_thenNavFieldsMarkedNoData() throws Exception {
        when(analytics.nav(9L)).thenReturn(Optional.empty());
        stubPortfolio();
        when(portfolioRepository.findTradesByPositionId(11L)).thenReturn(List.of(
                trade(101, 11L, TradeType.BUY, "2026-02-05", "10.50")));
        when(portfolioRepository.findTradesByPositionId(22L)).thenReturn(List.of());
        when(stockClose.closes(eq(CODE), any(LocalDate.class), eq(AS_OF)))
                .thenReturn(closes("2026-02-06", "10.80"));

        String json = composer.compose(9L, project(), FROM, TO, List.of());

        JsonNode root = mapper.readTree(json);
        assertThat(root.path("navSeries").isTextual()).isTrue();
        assertThat(root.path("navSeries").asText()).isEqualTo("无数据");
        assertThat(root.path("periodReturn").isTextual()).isTrue();
        assertThat(root.path("periodReturn").asText()).isEqualTo("无数据");
        // trades 侧不受 nav 缺失影响
        assertThat(root.path("trades").isArray()).isTrue();
        assertThat(root.path("trades")).hasSize(1);
    }

    @DisplayName("nav 序列不覆盖复盘区间（区间早于首事件日）：同样标注「无数据」")
    @Test
    void givenNavOutsideWindow_whenCompose_thenNavFieldsMarkedNoData() throws Exception {
        when(analytics.nav(9L)).thenReturn(Optional.of(nav("2026-01-05", "1000", "2026-01-20", "1050")));
        stubPortfolio();
        when(portfolioRepository.findTradesByPositionId(11L)).thenReturn(List.of());
        when(portfolioRepository.findTradesByPositionId(22L)).thenReturn(List.of());

        String json = composer.compose(9L, project(), FROM, TO, List.of());

        JsonNode root = mapper.readTree(json);
        assertThat(root.path("navSeries").asText()).isEqualTo("无数据");
        assertThat(root.path("periodReturn").asText()).isEqualTo("无数据");
    }

    // ---- Focus 1/2：无圈内交易 →「无数据」；窗外交易不并入 ----

    @DisplayName("窗外交易（价在批次带外且日期在窗外的另一项目交易）：不并入且无圈内交易标注「无数据」（Focus 1+2）")
    @Test
    void givenOnlyOutOfWindowTrades_whenCompose_thenTradesNoDataAndExcluded() throws Exception {
        when(analytics.nav(9L)).thenReturn(Optional.of(nav(
                "2026-02-01", "1100", "2026-02-28", "1155")));
        stubPortfolio();
        // 唯一交易：2025-12（早于复盘区间、价 50 在批次带 [10,11] 外）→ 窗外
        when(portfolioRepository.findTradesByPositionId(11L)).thenReturn(List.of(
                trade(104, 11L, TradeType.BUY, "2025-12-05", "50.00")));
        when(portfolioRepository.findTradesByPositionId(22L)).thenReturn(List.of());

        String json = composer.compose(9L, project(), FROM, TO,
                List.of(new EntryBatch(1, new BigDecimal("10"), new BigDecimal("11"), 100, null,
                        new BigDecimal("0.5"))));

        JsonNode root = mapper.readTree(json);
        assertThat(root.path("trades").isTextual()).isTrue();
        assertThat(root.path("trades").asText()).isEqualTo("无数据");
        assertThat(root.path("tradeIds").isArray()).isTrue();
        assertThat(root.path("tradeIds")).isEmpty();
        // 无圈内交易 → 不再取收盘序列（按需查询）
        org.mockito.Mockito.verifyNoInteractions(stockClose);
    }

    @DisplayName("无组合：trades 标注「无数据」，nav 侧照常组装")
    @Test
    void givenNoPortfolio_whenCompose_thenTradesNoData() throws Exception {
        when(analytics.nav(9L)).thenReturn(Optional.empty());
        when(portfolioRepository.findPortfolioByUserId(9L)).thenReturn(Optional.empty());

        String json = composer.compose(9L, project(), FROM, TO, List.of());

        JsonNode root = mapper.readTree(json);
        assertThat(root.path("trades").asText()).isEqualTo("无数据");
        assertThat(root.path("tradeIds")).isEmpty();
    }

    // ---- Focus 5：跨持仓 trade_ids 去重排序 ----

    @DisplayName("trade_ids 跨持仓去重排序落库（Focus 5）")
    @Test
    void givenTradesAcrossPositions_whenCompose_thenTradeIdsSorted() throws Exception {
        when(analytics.nav(9L)).thenReturn(Optional.empty());
        stubPortfolio();
        // 持仓 22 的交易 id 小于持仓 11 的 → 合并后升序
        when(portfolioRepository.findTradesByPositionId(11L)).thenReturn(List.of(
                trade(205, 11L, TradeType.BUY, "2026-02-03", "10.50")));
        when(portfolioRepository.findTradesByPositionId(22L)).thenReturn(List.of(
                trade(103, 22L, TradeType.SELL, "2026-02-10", "11.00")));
        when(stockClose.closes(eq(CODE), any(LocalDate.class), eq(AS_OF)))
                .thenReturn(closes("2026-02-11", "11.50"));

        String json = composer.compose(9L, project(), FROM, TO, List.of());

        JsonNode root = mapper.readTree(json);
        StringBuilder ids = new StringBuilder();
        root.path("tradeIds").forEach(n -> ids.append(n.asLong()).append(","));
        assertThat(ids.toString()).isEqualTo("103,205,");
        // trades 明细同序（按日期）
        assertThat(root.path("trades").get(0).path("id").asLong()).isEqualTo(205L);
        assertThat(root.path("trades").get(1).path("id").asLong()).isEqualTo(103L);
    }

    // ---- 后续走势缺数据：不留白 ----

    @DisplayName("trade 后无收盘序列：afterMaxClose/afterMinClose 标注「无数据」非 null（Focus 1 延伸）")
    @Test
    void givenNoSubsequentCloses_whenCompose_thenAfterFieldsNoData() throws Exception {
        when(analytics.nav(9L)).thenReturn(Optional.empty());
        stubPortfolio();
        when(portfolioRepository.findTradesByPositionId(11L)).thenReturn(List.of(
                trade(101, 11L, TradeType.BUY, "2026-02-05", "10.50")));
        when(portfolioRepository.findTradesByPositionId(22L)).thenReturn(List.of());
        when(stockClose.closes(eq(CODE), any(LocalDate.class), eq(AS_OF)))
                .thenReturn(closes("2026-02-04", "10.40")); // 仅早于 trade 的收盘

        String json = composer.compose(9L, project(), FROM, TO, List.of());

        JsonNode trade = mapper.readTree(json).path("trades").get(0);
        assertThat(trade.path("afterMaxClose").asText()).isEqualTo("无数据");
        assertThat(trade.path("afterMinClose").asText()).isEqualTo("无数据");
    }
}
