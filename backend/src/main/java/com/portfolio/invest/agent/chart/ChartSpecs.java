package com.portfolio.invest.agent.chart;

import com.portfolio.invest.application.industry.IndustryBoardView;
import com.portfolio.invest.application.valuation.ValuationOverviewView;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.market.DuPontAnalysis;
import com.portfolio.invest.domain.market.FinancialRecord;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.IndexQuote;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 域数据 → ChartSpec/摘要文本。title/label 的文案是 e2e 断言锚点，改动须同步 P5。 */
public final class ChartSpecs {
  public static final int SPEC_VERSION = 1;

  private ChartSpecs() {}

  // ———— get_kline → candlestick ————

  public static ChartSpec kline(String code, String period, List<KlineBar> bars, int... maWindows) {
    String p = period == null ? "day" : period;
    List<String> dates = bars.stream().map(KlineBar::date).toList();
    // ⚠ 顺序 [开,收,低,高]（ECharts 官方约定；KlineBar 字段顺序是 open,close,high,low，别按字段声明顺序抄）
    List<double[]> klines = bars.stream()
            .map(b -> new double[] {b.open(), b.close(), b.low(), b.high()}).toList();
    List<Long> volumes = bars.stream().map(KlineBar::volume).toList();
    List<ChartSpec.MaLine> mas = new ArrayList<>();
    for (int w : (maWindows.length == 0 ? new int[] {5, 20} : maWindows)) {
      mas.add(new ChartSpec.MaLine("MA" + w, ma(bars, w)));
    }
    return new ChartSpec.Candlestick(SPEC_VERSION, "candlestick",
            "%s %sK线".formatted(code, periodLabel(p)), null, code, p, dates, klines, volumes, mas);
  }

  public static String klineSummary(String code, String period, List<KlineBar> bars) {
    double latest = bars.get(bars.size() - 1).close();
    double lo = bars.stream().mapToDouble(KlineBar::low).min().orElse(latest);
    double hi = bars.stream().mapToDouble(KlineBar::high).max().orElse(latest);
    return "%s %sK %d根：最新 %.2f，区间 [%.2f, %.2f]"
            .formatted(code, periodLabel(period), bars.size(), latest, lo, hi);
  }

  // ———— 空数据安全摘要（InvestTools emit 前守卫用）：不 emit 空 spec，LLM 收文本 ————

  public static String klineEmptySummary(String code, String period) {
    return "%s %sK 暂无数据".formatted(code, periodLabel(period));
  }

  public static String valuationEmptySummary() {
    return "估值数据积累中，暂无历史走势。";
  }

  public static String financialsEmptySummary(Financials f) {
    return "%s（%s）暂无财务数据".formatted(f.name(), f.code());
  }

  private static List<Double> ma(List<KlineBar> bars, int window) {
    List<Double> out = new ArrayList<>(bars.size());
    double sum = 0;
    for (int i = 0; i < bars.size(); i++) {
      sum += bars.get(i).close();
      if (i >= window) sum -= bars.get(i - window).close();
      out.add(i >= window - 1 ? sum / window : null);   // 预热期 null，与 dates 对齐
    }
    return out;
  }

  private static String periodLabel(String period) {
    return switch (period == null ? "day" : period) {
      case "week" -> "周"; case "month" -> "月"; default -> "日";
    };
  }

  // ———— get_valuation → line（全A PE/PB 中位数历史，数据来自 history()）————

  public static ChartSpec valuationLine(List<ValuationSnapshot> snapshots) {
    List<String> dates = snapshots.stream().map(s -> s.tradingDay().toString()).toList();
    return new ChartSpec.Line(SPEC_VERSION, "line", "全A 估值中位数走势", null, dates, List.of(
            new ChartSpec.Series("PE", snapshots.stream().map(s -> nullable(s.peMedian())).toList(), null),
            new ChartSpec.Series("PB", snapshots.stream().map(s -> nullable(s.pbMedian())).toList(), null)),
        null);
  }

  /**
   * 摘要用 overview() 标量（分位/ERP/温度计/指数估值——设计规格 §二.3）；n 为图表交易日数。
   * latestSnapshot 冷库期可为 null（ValuationApplicationService 空表合法产出）——判空跳段，其余标量照常。
   */
  public static String valuationSummary(ValuationOverviewView o, int n) {
    StringBuilder sb = new StringBuilder("估值概览");
    var s = o.latestSnapshot();
    if (s != null) {
      sb.append("(%s)：全A PE 中位数 %s（%s 分位）、PB 中位数 %s（%s 分位）；"
              .formatted(s.tradingDay(), s.peMedian(), o.pePercentile(), s.pbMedian(), o.pbPercentile()));
    } else {
      sb.append("：");
    }
    sb.append("ERP %s%%（%s 分位）；情绪温度计 %s/100。".formatted(o.erp(), o.erpPercentile(), o.thermometer()));
    // 指数估值段：一行循环各指数（冷库期指数名为空/PE 为 null 的占位条目跳过；共 5 个，长度可控）
    String indices = o.indices() == null ? "" : o.indices().stream()
            .filter(i -> i.pe() != null && i.indexName() != null && !i.indexName().isEmpty())
            .map(i -> "%s PE %s（%s 分位）".formatted(i.indexName(), i.pe(), i.pePercentile()))
            .collect(Collectors.joining(" "));
    if (!indices.isEmpty()) {
      sb.append("指数估值：").append(indices).append("。");
    }
    sb.append("附图：PE/PB 中位数历史 %d 个交易日。".formatted(n));
    return sb.toString();
  }

  // ———— get_market_overview → bar（仅涨跌幅，点位进摘要）————

  public static ChartSpec overviewBar(MarketOverview overview) {
    List<IndexQuote> idx = overview.indices();
    return new ChartSpec.Bar(SPEC_VERSION, "bar", "主要指数涨跌幅", null,
            idx.stream().map(IndexQuote::name).toList(),
            List.of(new ChartSpec.Series("涨跌幅",
                    idx.stream().map(i -> (Double) i.changePct()).toList(), null)),
            null, "%");
  }

  public static String overviewSummary(MarketOverview overview) {
    StringBuilder sb = new StringBuilder("大盘速览(").append(overview.time()).append(")：");
    for (IndexQuote i : overview.indices()) {
      sb.append("%s %s（%s%.2f%%）、".formatted(i.name(), trim(i.price()), i.changePct() >= 0 ? "+" : "", i.changePct()));
    }
    return sb.deleteCharAt(sb.length() - 1).toString();
  }

  // ———— get_financials → table ————

  public static ChartSpec financialsTable(Financials f) {
    List<ChartSpec.Column> columns = List.of(
            new ChartSpec.Column("reportDate", "报告期", null, null),
            new ChartSpec.Column("eps", "每股收益EPS", "right", null),
            new ChartSpec.Column("bps", "每股净资产BPS", "right", null),
            new ChartSpec.Column("revenueYi", "营收(亿元)", "right", null),
            new ChartSpec.Column("netProfitYi", "净利润(亿元)", "right", null),
            new ChartSpec.Column("roe", "加权ROE(%)", "right", null),
            new ChartSpec.Column("grossMargin", "毛利率(%)", "right", null));
    List<Map<String, Object>> rows = new ArrayList<>();
    for (var i : f.indicators()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("reportDate", i.reportDate());
      row.put("eps", i.eps());
      row.put("bps", i.bps());
      row.put("revenueYi", round2Yi(i.totalRevenue()));   // 元 → 亿元，两位小数
      row.put("netProfitYi", round2Yi(i.netProfit()));
      row.put("roe", i.weightedRoe());
      row.put("grossMargin", i.grossMargin());
      rows.add(row);
    }
    return new ChartSpec.Table(SPEC_VERSION, "table",
            "%s %s 财务指标".formatted(f.code(), f.name()), null, columns, rows, null);
  }

  public static String financialsSummary(Financials f) {
    // 真实管线降序（EastmoneyClient sortTypes=-1），首期即最新——与 OrchestratingMarketDataService 同一不变式
    var last = f.indicators().get(0);
    return "%s（%s）：PE %s / PB %s；最新报告期 %s：EPS %s、营收 %s亿、净利 %s亿、加权ROE %s%%、毛利率 %s%%。共 %d 期，明细见表格。"
            .formatted(f.name(), f.code(), f.pe(), f.pb(), last.reportDate(), last.eps(),
                    round2Yi(last.totalRevenue()), round2Yi(last.netProfit()),
                    last.weightedRoe(), last.grossMargin(), f.indicators().size());
  }

    // ===== MS-12（F09/F10）=====

    /** F09 资产配置饼图（资产类市值，亿元；类别名用中文 label）。 */
    public static ChartSpec portfolioPie(
            com.portfolio.invest.application.portfolio.AssetAllocationView allocation) {
        List<ChartSpec.Slice> data = allocation.slices().stream()
                .map(s -> new ChartSpec.Slice(s.category().label(),
                        Math.round(s.marketValue().doubleValue() / 1e8 * 10) / 10.0))
                .toList();
        return new ChartSpec.Pie(SPEC_VERSION, "pie", "当前资产配置", "市值（亿元）", data, "亿元");
    }

    public static String portfolioSummary(
            com.portfolio.invest.application.portfolio.PortfolioOverviewView overview,
            com.portfolio.invest.application.portfolio.ConcentrationView concentration,
            com.portfolio.invest.application.portfolio.IndustryDistributionView distribution) {
        StringBuilder sb = new StringBuilder("总资产 %.1f 亿（成本 %.1f 亿，浮动盈亏 %.1f 亿），共 %d 只持仓。"
                .formatted(overview.totalAssets().doubleValue() / 1e8,
                        overview.totalCost().doubleValue() / 1e8,
                        overview.totalPnl().doubleValue() / 1e8, overview.positionCount()));
        if (concentration.top5Ratio() != null) {
            sb.append("前五大占比 %.1f%%".formatted(concentration.top5Ratio().doubleValue() * 100));
            concentration.holdings().stream().findFirst().ifPresent(h ->
                    sb.append("，第一大 %s(%s) %.1f%%".formatted(
                            h.stockName(), h.stockCode(), h.ratio().doubleValue() * 100)));
            sb.append("。");
        }
        if (!distribution.slices().isEmpty()) {
            sb.append("行业分布：");
            for (int i = 0; i < Math.min(3, distribution.slices().size()); i++) {
                var s = distribution.slices().get(i);
                sb.append(i == 0 ? "" : "、").append("%s %.1f%%"
                        .formatted(s.industryName(), s.ratio().doubleValue() * 100));
            }
            sb.append("。配置结构饼图见附图。");
        }
        return sb.toString();
    }

    /**
     * F10 推荐配置 vs 当前分布对照表（权重百分点）。
     * 当前分布的 AllocationSliceCategory 只有 EQUITY/CASH 两类（M08 口径），映射到
     * AssetClass.STOCK/CASH；BOND/GOLD/REITS 当前必为 0。
     */
    public static ChartSpec allocationDeviationTable(String profileName,
            List<com.portfolio.invest.application.allocation.WeightView> recommended,
            com.portfolio.invest.application.portfolio.AssetAllocationView current) {
        Map<com.portfolio.invest.domain.allocation.AssetClass, Double> actual = new LinkedHashMap<>();
        for (var s : current.slices()) {
            com.portfolio.invest.domain.allocation.AssetClass ac = switch (s.category()) {
                case EQUITY -> com.portfolio.invest.domain.allocation.AssetClass.STOCK;
                case CASH -> com.portfolio.invest.domain.allocation.AssetClass.CASH;
            };
            actual.put(ac, s.ratio() == null ? null : s.ratio().doubleValue() * 100);
        }
        List<ChartSpec.Column> columns = List.of(
                new ChartSpec.Column("assetClass", "资产类别", null, null),
                new ChartSpec.Column("target", "推荐权重%", "right", null),
                new ChartSpec.Column("actual", "当前权重%", "right", null),
                new ChartSpec.Column("deviation", "偏离pp", "right", null));
        List<Map<String, Object>> rows = new ArrayList<>();
        java.util.EnumSet<com.portfolio.invest.domain.allocation.AssetClass> seen =
                java.util.EnumSet.noneOf(com.portfolio.invest.domain.allocation.AssetClass.class);
        for (var w : recommended) {
            seen.add(w.assetClass());
            double target = w.weight().doubleValue() * 100;
            Double act = actual.get(w.assetClass());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assetClass", w.assetClass().label());
            row.put("target", Math.round(target * 10) / 10.0);
            row.put("actual", act == null ? 0.0 : Math.round(act * 10) / 10.0);
            row.put("deviation", Math.round(((act == null ? 0.0 : act) - target) * 10) / 10.0);
            rows.add(row);
        }
        for (var e : actual.entrySet()) {
            if (seen.contains(e.getKey()) || e.getValue() == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("assetClass", e.getKey().label());
            row.put("target", 0.0);
            row.put("actual", Math.round(e.getValue() * 10) / 10.0);
            row.put("deviation", Math.round(e.getValue() * 10) / 10.0);
            rows.add(row);
        }
        return new ChartSpec.Table(SPEC_VERSION, "table", "推荐配置 vs 当前（%s型）".formatted(profileName),
                null, columns, rows, null);
    }

    public static String allocationSummary(
            com.portfolio.invest.application.allocation.AssessmentView assessment,
            com.portfolio.invest.application.allocation.DeviationView deviation) {
        StringBuilder sb = new StringBuilder("你的风险测评结果：%s型（总分 %d），推荐配置见对照表。"
                .formatted(assessment.profileName(), assessment.totalScore()));
        if (!deviation.slices().isEmpty()) {
            sb.append("当前生效方案偏离：");
            boolean first = true;
            for (var s : deviation.slices()) {
                double pp = s.deviation().doubleValue() * 100;
                if (Math.abs(pp) < 0.05) continue;
                sb.append(first ? "" : "、").append("%s %+.1fpp".formatted(s.assetClass().label(), pp));
                first = false;
            }
            sb.append("。");
        } else {
            sb.append("尚无生效配置方案，可到配置页创建。");
        }
        return sb.toString();
    }

    // ===== MS-12（F08/F11/F12）=====

    /** F08 筛选结果表：8 列，市值/亿 = 元/1e8 一位小数（与前端 fmtMv 口径一致）。 */
    public static ChartSpec screeningTable(List<StockScreeningResult> results) {
        List<ChartSpec.Column> columns = List.of(
                new ChartSpec.Column("stockCode", "代码", null, null),
                new ChartSpec.Column("stockName", "名称", null, null),
                new ChartSpec.Column("industryName", "行业", null, null),
                new ChartSpec.Column("peTtm", "PE(TTM)", "right", null),
                new ChartSpec.Column("pb", "PB", "right", null),
                new ChartSpec.Column("roe", "ROE%", "right", null),
                new ChartSpec.Column("dividendYield", "股息率%", "right", null),
                new ChartSpec.Column("totalMvYi", "总市值(亿)", "right", null));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (StockScreeningResult r : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stockCode", r.stockCode());
            row.put("stockName", r.stockName());
            row.put("industryName", r.industryName());
            row.put("peTtm", r.peTtm());
            row.put("pb", r.pb());
            row.put("roe", r.roe());
            row.put("dividendYield", r.dividendYield());
            row.put("totalMvYi", r.totalMv() == null ? null
                    : Math.round(r.totalMv().doubleValue() / 1e8 * 10) / 10.0);
            rows.add(row);
        }
        return new ChartSpec.Table(SPEC_VERSION, "table", "筛选结果（%d 只）".formatted(results.size()),
                null, columns, rows, null);
    }

    public static String screeningSummary(List<StockScreeningResult> results) {
        if (results.isEmpty()) return "无符合条件的股票。可放宽条件后重试。";
        StringBuilder sb = new StringBuilder("共筛出 %d 只：".formatted(results.size()));
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            StockScreeningResult r = results.get(i);
            sb.append(i == 0 ? "" : "；").append("%s(%s PE %s ROE %s%%)"
                    .formatted(r.stockName(), r.stockCode(), r.peTtm(), r.roe()));
        }
        if (results.size() > 5) sb.append("；其余见附表。");
        return sb.toString();
    }

    /** F11 12 季趋势表（报告期降序）。 */
    public static ChartSpec financialTrendTable(String code, String name, List<FinancialRecord> records) {
        List<ChartSpec.Column> columns = List.of(
                new ChartSpec.Column("reportDate", "报告期", null, null),
                new ChartSpec.Column("roe", "ROE%", "right", null),
                new ChartSpec.Column("roa", "ROA%", "right", null),
                new ChartSpec.Column("grossMargin", "毛利率%", "right", null),
                new ChartSpec.Column("debtToAssets", "资产负债率%", "right", null),
                new ChartSpec.Column("revenueYi", "营收(亿)", "right", null),
                new ChartSpec.Column("revenueYoy", "营收同比%", "right", null),
                new ChartSpec.Column("netprofitYoy", "净利同比%", "right", null));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (FinancialRecord r : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("reportDate", r.reportDate().toString()); // 与 financialsTable 同口径：wire 行只放字符串
            row.put("roe", r.roe());
            row.put("roa", r.roa());
            row.put("grossMargin", r.grossMargin());
            row.put("debtToAssets", r.debtToAssets());
            row.put("revenueYi", r.revenue() == null ? null
                    : Math.round(r.revenue().doubleValue() / 1e8 * 10) / 10.0);
            row.put("revenueYoy", r.revenueYoy());
            row.put("netprofitYoy", r.netprofitYoy());
            rows.add(row);
        }
        return new ChartSpec.Table(SPEC_VERSION, "table", "%s %s 财务趋势（近 %d 季）"
                .formatted(code, name, records.size()), null, columns, rows, null);
    }

    public static String financialTrendSummary(String name, int quarters, DuPontAnalysis.DuPontResult d) {
        if (d == null) return "%s 暂无库表财务数据（新股或数据积累中）。".formatted(name);
        StringBuilder sb = new StringBuilder("%s 杜邦拆解（库表期 %s".formatted(name, d.recordReportDate()));
        if (d.liveReportDate() != null) sb.append("，净利率取 live 期 ").append(d.liveReportDate());
        sb.append("）：");
        if (d.netMargin() != null) sb.append("净利率 %.1f%%、".formatted(d.netMargin() * 100));
        if (d.assetTurnover() != null) sb.append("总资产周转率 %.2f 次、".formatted(d.assetTurnover()));
        if (d.equityMultiplier() != null) sb.append("权益乘数 %.2f 倍、".formatted(d.equityMultiplier()));
        if (d.roePercent() != null) sb.append("对应 ROE %.1f%%。".formatted(d.roePercent()));
        if (!d.missingFactors().isEmpty()) sb.append("缺失因子：").append(String.join("、", d.missingFactors())).append("。");
        sb.append("近 %d 季趋势见附表。".formatted(quarters));
        return sb.toString();
    }

    /** F12 全行业估值板面表。 */
    public static ChartSpec industryBoardTable(List<IndustryBoardView> board) {
        List<ChartSpec.Column> columns = List.of(
                new ChartSpec.Column("industryName", "行业", null, null),
                new ChartSpec.Column("pe", "PE", "right", null),
                new ChartSpec.Column("pb", "PB", "right", null),
                new ChartSpec.Column("roe", "ROE%", "right", null),
                new ChartSpec.Column("dividendYield", "股息率%", "right", null),
                new ChartSpec.Column("pePercentile", "PE分位%", "right", null),
                new ChartSpec.Column("pbPercentile", "PB分位%", "right", null));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IndustryBoardView b : board) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("industryName", b.industryName());
            row.put("pe", b.pe());
            row.put("pb", b.pb());
            row.put("roe", b.roe());
            row.put("dividendYield", b.dividendYield());
            row.put("pePercentile", b.pePercentile());
            row.put("pbPercentile", b.pbPercentile());
            rows.add(row);
        }
        return new ChartSpec.Table(SPEC_VERSION, "table", "行业估值板面（%d 个一级行业）"
                .formatted(board.size()), null, columns, rows, null);
    }

    public static String industryBoardSummary(List<IndustryBoardView> board) {
        if (board.isEmpty()) return "行业板面暂无数据（估值快照积累中）。";
        var lowest = board.stream().filter(b -> b.pePercentile() != null)
                .min(java.util.Comparator.comparing(IndustryBoardView::pePercentile)).orElse(null);
        var highest = board.stream().filter(b -> b.pePercentile() != null)
                .max(java.util.Comparator.comparing(IndustryBoardView::pePercentile)).orElse(null);
        StringBuilder sb = new StringBuilder("全行业估值板面（%d 个）".formatted(board.size()));
        if (lowest != null) sb.append("；估值分位最低：%s（PE分位 %s%%）")
                .append(lowest.industryName()).append(lowest.pePercentile());
        if (highest != null) sb.append("；最高：%s（PE分位 %s%%）")
                .append(highest.industryName()).append(highest.pePercentile());
        sb.append("。明细见附表；指定行业可传行业码下钻头部企业。");
        return sb.toString();
    }

    /** F12 行业头部企业表。 */
    public static ChartSpec industryStocksTable(String industryName, List<IndustryStock> stocks) {
        List<ChartSpec.Column> columns = List.of(
                new ChartSpec.Column("stockCode", "代码", null, null),
                new ChartSpec.Column("stockName", "名称", null, null),
                new ChartSpec.Column("totalMvYi", "总市值(亿)", "right", null),
                new ChartSpec.Column("revenueYi", "营收(亿)", "right", null),
                new ChartSpec.Column("roe", "ROE%", "right", null),
                new ChartSpec.Column("peTtm", "PE(TTM)", "right", null),
                new ChartSpec.Column("pb", "PB", "right", null),
                new ChartSpec.Column("dividendYield", "股息率%", "right", null));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (IndustryStock s : stocks) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stockCode", s.stockCode());
            row.put("stockName", s.stockName());
            row.put("totalMvYi", s.totalMv() == null ? null
                    : Math.round(s.totalMv().doubleValue() / 1e8 * 10) / 10.0);
            row.put("revenueYi", s.revenue() == null ? null
                    : Math.round(s.revenue().doubleValue() / 1e8 * 10) / 1.0);
            row.put("roe", s.roe());
            row.put("peTtm", s.peTtm());
            row.put("pb", s.pb());
            row.put("dividendYield", s.dividendYield());
            rows.add(row);
        }
        return new ChartSpec.Table(SPEC_VERSION, "table", "%s 头部企业（%d 只，按市值降序）"
                .formatted(industryName, stocks.size()), null, columns, rows, null);
    }

    public static String industryStocksSummary(IndustryBoardView row, List<IndustryStock> stocks) {
        if (stocks.isEmpty()) return "%s 行业暂无成分股数据。".formatted(row.industryName());
        IndustryStock top = stocks.get(0);
        return "%s（%s）：PE %s / PB %s，PE分位 %s%%；头部企业 %d 只，市值第一 %s（%s，PE %s）。明细见附表。"
                .formatted(row.industryName(), row.industryCode(), row.pe(), row.pb(),
                        row.pePercentile(), stocks.size(), top.stockName(), top.stockCode(), top.peTtm());
    }

  private static Double nullable(BigDecimal v) { return v == null ? null : v.doubleValue(); }
  /** 元 → 亿元，两位小数。T1：FinancialIndicator 的 Double 字段可空，null 先除法拆箱会 NPE → 保 null（行输出 null 而非 0）。 */
  private static Double round2Yi(Double yuan) { return yuan == null ? null : Math.round(yuan / 1e8 * 100) / 100d; }
  private static String trim(double price) { return String.format("%.2f", price); }
}
