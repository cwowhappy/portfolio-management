package com.portfolio.invest.agent.chart;

import com.portfolio.invest.application.valuation.ValuationOverviewView;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.IndexQuote;
import com.portfolio.invest.domain.market.KlineBar;
import com.portfolio.invest.domain.market.MarketOverview;
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

  private static Double nullable(BigDecimal v) { return v == null ? null : v.doubleValue(); }
  /** 元 → 亿元，两位小数。T1：FinancialIndicator 的 Double 字段可空，null 先除法拆箱会 NPE → 保 null（行输出 null 而非 0）。 */
  private static Double round2Yi(Double yuan) { return yuan == null ? null : Math.round(yuan / 1e8 * 100) / 100d; }
  private static String trim(double price) { return String.format("%.2f", price); }
}
