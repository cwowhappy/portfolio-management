import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render } from "@testing-library/react";

const renderToolConfigs: { name: string; render: (p: Record<string, unknown>) => React.ReactElement }[] = [];
vi.mock("@copilotkit/react-core/v2", () => ({
  useRenderTool: (config: { name: string; render: (p: Record<string, unknown>) => React.ReactElement }) => {
    renderToolConfigs.push(config);
  },
}));

vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

import { ChartToolRenderers } from "@/components/chat/toolRenderers";

afterEach(() => { cleanup(); renderToolConfigs.length = 0; });

const klineSpec = {
  specVersion: 1, type: "candlestick", title: "600519 贵州茅台 日K",
  symbol: "600519", period: "day", dates: ["09-09"], klines: [[1800, 1850, 1790, 1860]],
};
const lineSpec = {
  specVersion: 1, type: "line", title: "全A 估值中位数走势",
  categories: ["08-01", "08-02"], series: [{ name: "PE", data: [25.1, 25.3] }, { name: "PB", data: [2.1, null] }],
};
const barSpec = {
  specVersion: 1, type: "bar", title: "主要指数涨跌幅", unit: "%",
  categories: ["上证指数", "深证成指", "创业板指"], series: [{ name: "涨跌幅", data: [0.8, -0.3, 1.2] }],
};
// financials.json fixture 的最小形态（双端契约真源，Task 2 表格卡端到端）
const tableSpec = {
  specVersion: 1, type: "table", title: "600519 贵州茅台 财务指标",
  columns: [{ key: "reportDate", label: "报告期" }, { key: "eps", label: "每股收益EPS", align: "right" }],
  rows: [{ reportDate: "2026-06-30", eps: 24.2 }],
};

describe("ChartToolRenderers", () => {
  it("注册 4 既有 + 5 MS-12 + research_draft + search_news + search_announcements + macro_brief 具名渲染器（无 agentId，05 §4.1）", () => {
    render(<ChartToolRenderers />);
    expect(renderToolConfigs.map((c) => c.name)).toEqual(
      ["get_kline", "get_valuation", "get_market_overview", "get_financials",
        "screen_stocks", "analyze_financials", "analyze_industry", "suggest_allocation", "analyze_portfolio",
        "research_draft", "search_news", "search_announcements", "macro_brief"]);
    expect(renderToolConfigs.every((c) => "parameters" in c)).toBe(true);
  });

  it("get_kline complete → candlestick option（klines 透传）", () => {
    render(<ChartToolRenderers />);
    const kline = renderToolConfigs.find((c) => c.name === "get_kline")!;
    const { getByTestId } = render(kline.render({ status: "complete", result: JSON.stringify(klineSpec) }) as React.ReactElement);
    const option = JSON.parse(getByTestId("chart-card-chart").dataset.option!);
    expect(option.series.find((s: { type: string }) => s.type === "candlestick").data).toEqual(klineSpec.klines);
  });

  it("get_valuation → line（null 缺口保留）；get_market_overview → bar（类目=指数名）", () => {
    render(<ChartToolRenderers />);
    const valuation = renderToolConfigs.find((c) => c.name === "get_valuation")!;
    const { getByTestId: g1 } = render(valuation.render({ status: "complete", result: JSON.stringify(lineSpec) }) as React.ReactElement);
    // 同一 dataset.option 只解析一次，两条断言共用
    const lineOption = JSON.parse(g1("chart-card-chart").dataset.option!);
    expect(lineOption.series[0].data).toEqual([25.1, 25.3]);
    expect(lineOption.series[1].data).toEqual([2.1, null]);
    // 同一 it 内二次 render：RTL 查询绑定 document.body，先 cleanup 隔离，否则 getByTestId 撞多元素
    cleanup();
    const overview = renderToolConfigs.find((c) => c.name === "get_market_overview")!;
    const { getByTestId: g2 } = render(overview.render({ status: "complete", result: JSON.stringify(barSpec) }) as React.ReactElement);
    const barOption = JSON.parse(g2("chart-card-chart").dataset.option!);
    expect(barOption.xAxis.data).toEqual(barSpec.categories);
  });

  it("get_financials complete + TableSpec JSON → 表头渲染（financials fixture 最小形态）", () => {
    render(<ChartToolRenderers />);
    const financials = renderToolConfigs.find((c) => c.name === "get_financials")!;
    const { getByText } = render(financials.render({ status: "complete", result: JSON.stringify(tableSpec) }) as React.ReactElement);
    expect(getByText("报告期")).toBeTruthy();
    expect(getByText("每股收益EPS")).toBeTruthy();
  });

  it("inProgress 半截参数（Partial）→ 骨架不崩", () => {
    render(<ChartToolRenderers />);
    const kline = renderToolConfigs.find((c) => c.name === "get_kline")!;
    const { container } = render(kline.render({ status: "inProgress", parameters: { code: "60" } }) as React.ReactElement);
    expect(container.querySelector(".tool-card.running")).toBeTruthy();
  });

  it("错误 result → 降级折叠卡", () => {
    render(<ChartToolRenderers />);
    const kline = renderToolConfigs.find((c) => c.name === "get_kline")!;
    const { container } = render(kline.render({ status: "complete", result: '{"error":"Tool execution failed: x"}' }) as React.ReactElement);
    expect(container.querySelector("details.tool-card")).toBeTruthy();
  });

  // ===== research_draft（invest-sop P1，Ruling-1：按工具名注册，渲染器提取围栏）=====
  it("research_draft complete + 围栏结果 → DraftCard 渲染（提取围栏内 JSON，摘要文案不进卡片）", () => {
    render(<ChartToolRenderers />);
    const rd = renderToolConfigs.find((c) => c.name === "research_draft")!;
    const result =
      "策略草稿已回显：估值区间 12.5~18.0\n```research-draft\n" +
      JSON.stringify({ specVersion: 1, stage: "STRATEGY", thesis: "高端白酒需求刚性", valuationLow: 12.5, valuationHigh: 18 }) +
      "\n```";
    const { getByText, queryByText } = render(rd.render({ status: "complete", result }) as React.ReactElement);
    expect(getByText("投研草稿 · 策略")).toBeTruthy();
    expect(getByText("高端白酒需求刚性")).toBeTruthy();
    expect(getByText("12.5~18")).toBeTruthy();
    expect(queryByText(/策略草稿已回显/)).toBeNull(); // 摘要留在工具结果文本，不重复进卡
  });

  it("research_draft 无围栏（参数错误文本）→ DraftCard 降级卡；inProgress → 骨架", () => {
    render(<ChartToolRenderers />);
    const rd = renderToolConfigs.find((c) => c.name === "research_draft")!;
    const { getByText } = render(
      rd.render({ status: "complete", result: "[research_draft] 参数错误：未知 stage: STAGE_X" }) as React.ReactElement,
    );
    expect(getByText(/草稿格式不兼容/)).toBeTruthy();
    cleanup();
    const { container } = render(rd.render({ status: "inProgress", name: "research_draft" }) as React.ReactElement);
    expect(container.querySelector(".tool-card.running")).toBeTruthy();
  });

  // ===== search_news（MS-20 Task 12）：列表卡——标题链接/方向徽标/重要度/摘要/关键数字/时间 =====
  const newsResult = JSON.stringify({
    items: [
      {
        title: "茅台三季报预增",
        summary: "AI 摘要：净利润同比 +25%",
        direction: "BULLISH",
        importance: 72,
        keyNumbers: ["Q3 净利润同比 +25.3%"],
        stockCodes: ["600519"],
        url: "https://x/1",
        publishedAt: "2026-09-28T13:00:00Z",
      },
      {
        title: "白酒板块承压",
        summary: "AI 摘要：需求走弱",
        direction: "BEARISH",
        importance: 40,
        keyNumbers: [],
        stockCodes: [],
        url: null,
        publishedAt: "2026-09-27T13:00:00Z",
      },
    ],
    total: 27,
  });

  it("search_news complete → 列表卡：标题链接/无 url 退化纯文本/方向中文徽标/重要度/关键数字/total", () => {
    render(<ChartToolRenderers />);
    const sn = renderToolConfigs.find((c) => c.name === "search_news")!;
    const { getByText, getByRole } = render(sn.render({ status: "complete", result: newsResult }) as React.ReactElement);
    // 标题：有 url 为链接、无 url 退化纯文本
    expect(getByRole("link", { name: "茅台三季报预增" }).getAttribute("href")).toBe("https://x/1");
    expect(getByText("白酒板块承压")).toBeTruthy();
    // 方向中文映射：BULLISH=利好 / BEARISH=利空
    expect(getByText("利好")).toBeTruthy();
    expect(getByText("利空")).toBeTruthy();
    // 重要度 + total + 摘要行
    expect(getByText("重要度 72")).toBeTruthy();
    expect(getByText("共 27 条")).toBeTruthy();
    expect(getByText("AI 摘要：净利润同比 +25%")).toBeTruthy();
    expect(getByText("600519")).toBeTruthy();
    // 关键数字行（时间/标的码同区渲染；空数组条目 null 安全不渲染）
    expect(getByText("Q3 净利润同比 +25.3%")).toBeTruthy();
  });

  it("search_news 空结果 → message 行（90 天话术），无条目列表", () => {
    render(<ChartToolRenderers />);
    const sn = renderToolConfigs.find((c) => c.name === "search_news")!;
    const { getByText, container } = render(
      sn.render({ status: "complete", result: '{"items":[],"message":"该条件下暂无情报（新闻仅保留 90 天内）"}' }) as React.ReactElement,
    );
    expect(getByText("该条件下暂无情报（新闻仅保留 90 天内）")).toBeTruthy();
    expect(container.querySelector("ul")).toBeNull(); // 空信封无条目列表
  });

  it("search_news 错误 result → 降级折叠卡；inProgress → 骨架", () => {
    render(<ChartToolRenderers />);
    const sn = renderToolConfigs.find((c) => c.name === "search_news")!;
    const { container } = render(
      sn.render({ status: "complete", result: '{"error":"工具执行失败","hint":"请稍后重试"}' }) as React.ReactElement,
    );
    expect(container.querySelector("details.tool-card")).toBeTruthy();
    cleanup();
    const running = render(sn.render({ status: "inProgress" }) as React.ReactElement);
    expect(running.container.querySelector(".tool-card.running")).toBeTruthy();
  });

  // ===== search_announcements（MS-21 P2 Task 8）：公告列表卡——标题链接/类型徽标兜底链/
  // 六字段要点行/scope 回显 =====
  const announcementResult = JSON.stringify({
    items: [
      {
        title: "贵州茅台 2026 年半年度报告",
        stockCode: "600519",
        stockName: "贵州茅台",
        annTypes: ["PERIODIC_REPORT"],
        annTypeSource: "半年报",
        metrics: {
          revenueYi: 128.56,
          netProfitYi: 31.2,
          netProfitYoyPct: 25.3,
          deductedProfitYi: null,
          grossMarginPct: 91.5,
          dividendDesc: "每10股派2元",
          undisclosed: [],
        },
        pdfUrl: "https://x/1.pdf",
        publishedAt: "2026-09-28T13:00:00Z",
      },
      {
        title: "平安银行回购进展",
        stockCode: "000001",
        stockName: "平安银行",
        annTypes: [],
        annTypeSource: "回购公告",
        metrics: null,
        pdfUrl: null,
        publishedAt: "2026-09-27T13:00:00Z",
      },
    ],
    total: 17,
  });

  it("search_announcements complete → 列表卡：标题链接/类型徽标兜底链/六字段要点行/scope 回显/total", () => {
    render(<ChartToolRenderers />);
    const sa = renderToolConfigs.find((c) => c.name === "search_announcements")!;
    const { getByText, getByRole } = render(
      sa.render({ status: "complete", result: announcementResult, parameters: { scope: "holdings" } }) as React.ReactElement,
    );
    // 标题：有 pdfUrl 为链接、无 pdfUrl 退化纯文本
    expect(getByRole("link", { name: "贵州茅台 2026 年半年度报告" }).getAttribute("href")).toBe("https://x/1.pdf");
    expect(getByText("平安银行回购进展")).toBeTruthy();
    // 类型徽标：annTypes 中文；空数组退化 annTypeSource 兜底
    expect(getByText("定期报告")).toBeTruthy();
    expect(getByText("回购公告")).toBeTruthy();
    // 六字段要点行（metrics 非空字段才出、null 字段不渲染；同比/毛利率带符号与推送卡片同款；整行精确匹配避免祖先元素多命中）
    expect(getByText("营收 128.56 亿 · 归母净利 31.2 亿 · 净利同比 +25.3% · 毛利率 +91.5% · 分红 每10股派2元")).toBeTruthy();
    // 标的名/码行 + scope 回显 + total
    expect(getByText("贵州茅台 600519")).toBeTruthy();
    expect(getByText("持仓范围")).toBeTruthy();
    expect(getByText("共 17 条")).toBeTruthy();
  });

  it("search_announcements 空结果 → message 行（scope 引导语），无条目列表；all 不回显 scope", () => {
    render(<ChartToolRenderers />);
    const sa = renderToolConfigs.find((c) => c.name === "search_announcements")!;
    const { getByText, container } = render(
      sa.render({
        status: "complete",
        result: '{"items":[],"message":"未设置订阅标的——可先到「情报订阅」添加关注股票后再检索"}',
        parameters: { scope: "subscription" },
      }) as React.ReactElement,
    );
    expect(getByText("未设置订阅标的——可先到「情报订阅」添加关注股票后再检索")).toBeTruthy();
    expect(container.querySelector("ul")).toBeNull(); // 空信封无条目列表
    expect(getByText("订阅范围")).toBeTruthy(); // scope=subscription 回显
    cleanup();
    const all = render(
      sa.render({ status: "complete", result: announcementResult, parameters: { scope: "all" } }) as React.ReactElement,
    );
    expect(all.queryByText("订阅范围")).toBeNull(); // all 不回显 scope 徽标
  });

  it("search_announcements 错误 result → 降级折叠卡；inProgress → 骨架", () => {
    render(<ChartToolRenderers />);
    const sa = renderToolConfigs.find((c) => c.name === "search_announcements")!;
    const { container } = render(
      sa.render({ status: "complete", result: '{"error":"工具执行失败","hint":"请稍后重试"}' }) as React.ReactElement,
    );
    expect(container.querySelector("details.tool-card")).toBeTruthy();
    cleanup();
    const running = render(sa.render({ status: "inProgress" }) as React.ReactElement);
    expect(running.container.querySelector(".tool-card.running")).toBeTruthy();
  });

  // ===== macro_brief（MS-22 Task 6）：宏观简报卡——指标表 + 政策列表两段 =====
  const macroBriefResult = JSON.stringify({
    indicators: [
      {
        indicator: "CPI",
        value: 0.6,
        yoy: 0.6,
        period: "2026-09",
        periodType: "MONTH",
        series: [
          { period: "2026-09", value: 0.6 },
          { period: "2026-08", value: 0.5 },
          { period: "2026-07", value: 0.4 },
        ],
      },
      {
        indicator: "TY1Y",
        value: 1.45,
        period: "2026-09-30",
        periodType: "DAY",
        note: "国债收益率仅最新点、无历史序列",
      },
    ],
    policies: [
      {
        title: "央行降准",
        direction: "EASING",
        strength: "HIGH",
        areas: ["房地产", "基建"],
        summary: "降准 0.5 个百分点",
        confidence: "HIGH",
        isPolicy: true,
        url: "https://x/p1",
        publishedAt: "2026-09-28T09:30:00Z",
      },
      {
        title: "领导活动新闻",
        direction: "NEUTRAL",
        strength: "LOW",
        areas: [],
        summary: "非政策类动态（过滤兜底）",
        confidence: "LOW",
        isPolicy: false,
        url: "https://x/p2",
        publishedAt: "2026-09-27T09:30:00Z",
      },
    ],
    total: 12,
    missing: [{ indicator: "PMI", missing: true }],
    generatedAt: "2026-10-03T02:15:00Z",
  });

  it("macro_brief complete → 指标表（值/期别/近5期迷你串）+ TY note + 缺失行 + 政策列表（方向徽标/力度/领域/链接/非政策标记）", () => {
    render(<ChartToolRenderers />);
    const mb = renderToolConfigs.find((c) => c.name === "macro_brief")!;
    const { getByText, getByRole } = render(
      mb.render({ status: "complete", result: macroBriefResult }) as React.ReactElement,
    );
    // 指标表：indicator/value/period/近5期迷你串（最新在前 → 值降序串）
    expect(getByText("CPI")).toBeTruthy();
    expect(getByText("0.6")).toBeTruthy();
    expect(getByText("2026-09")).toBeTruthy();
    expect(getByText("0.6→0.5→0.4")).toBeTruthy();
    // TY 无历史：note 直接展示（勿当数据缺失）
    expect(getByText("国债收益率仅最新点、无历史序列")).toBeTruthy();
    expect(getByText("TY1Y")).toBeTruthy();
    // 缺失行渲染：指标码 + 「数据缺失」单元（不编造值）
    expect(getByText("PMI")).toBeTruthy();
    expect(getByText("数据缺失")).toBeTruthy();
    // 政策列表：标题链接 + 方向徽标（EASING=宽松）+ 力度 + 影响领域 + 摘要 + 非政策标记 + total
    expect(getByRole("link", { name: "央行降准" }).getAttribute("href")).toBe("https://x/p1");
    expect(getByText("宽松")).toBeTruthy();
    expect(getByText("强")).toBeTruthy();
    expect(getByText("房地产 / 基建")).toBeTruthy();
    expect(getByText("降准 0.5 个百分点")).toBeTruthy();
    expect(getByText("非政策类")).toBeTruthy();
    expect(getByText("共 12 条")).toBeTruthy();
  });

  // ===== P2-F4：外链消毒 safeUrl——javascript:/http: 拦截渲染纯文本（无 href），https 放行 =====
  it("search_news url=javascript: → 标题为无 href 的纯文本元素（外链消毒）", () => {
    render(<ChartToolRenderers />);
    const sn = renderToolConfigs.find((c) => c.name === "search_news")!;
    const { getByText } = render(
      sn.render({
        status: "complete",
        result: JSON.stringify({ items: [{ title: "恶链新闻", url: "javascript:alert(1)" }], total: 1 }),
      }) as React.ReactElement,
    );
    // 标题文本仍在，但不再是链接（closest("a") 为 null，不可点击执行）
    expect(getByText("恶链新闻").closest("a")).toBeNull();
  });

  it("search_announcements pdfUrl=http:// → 标题为无 href 的纯文本元素（外链消毒）", () => {
    render(<ChartToolRenderers />);
    const sa = renderToolConfigs.find((c) => c.name === "search_announcements")!;
    const { getByText } = render(
      sa.render({
        status: "complete",
        result: JSON.stringify({ items: [{ title: "明文公告", pdfUrl: "http://x.pdf" }], total: 1 }),
      }) as React.ReactElement,
    );
    expect(getByText("明文公告").closest("a")).toBeNull();
  });

  it("macro_brief url=javascript: → 无 href 纯文本；url=https://ok.com → <a> 且 href 原样", () => {
    render(<ChartToolRenderers />);
    const mb = renderToolConfigs.find((c) => c.name === "macro_brief")!;
    const { getByText, getByRole } = render(
      mb.render({
        status: "complete",
        result: JSON.stringify({
          indicators: [{ indicator: "CPI", value: 0.6, period: "2026-09", periodType: "MONTH", series: [] }],
          policies: [
            { title: "恶链政策", url: "javascript:alert(1)" },
            { title: "合规政策", url: "https://ok.com" },
          ],
          missing: [],
        }),
      }) as React.ReactElement,
    );
    expect(getByText("恶链政策").closest("a")).toBeNull();
    expect(getByRole("link", { name: "合规政策" }).getAttribute("href")).toBe("https://ok.com");
  });

  it("macro_brief 空政策 → note 行且无政策列表；错误 → 降级折叠卡；inProgress → 骨架", () => {
    render(<ChartToolRenderers />);
    const mb = renderToolConfigs.find((c) => c.name === "macro_brief")!;
    const empty = render(
      mb.render({
        status: "complete",
        result: JSON.stringify({
          indicators: [{ indicator: "CPI", value: 0.6, period: "2026-09", periodType: "MONTH", series: [] }],
          policies: [],
          note: "该窗口内暂无政策事件（可调大 policyDays 或稍后再试）",
          missing: [],
          generatedAt: "2026-10-03T02:15:00Z",
        }),
      }) as React.ReactElement,
    );
    expect(empty.getByText("该窗口内暂无政策事件（可调大 policyDays 或稍后再试）")).toBeTruthy();
    cleanup();
    const error = render(
      mb.render({ status: "complete", result: '{"error":"工具执行失败","hint":"请稍后重试"}' }) as React.ReactElement,
    );
    expect(error.container.querySelector("details.tool-card")).toBeTruthy();
    cleanup();
    const running = render(mb.render({ status: "inProgress" }) as React.ReactElement);
    expect(running.container.querySelector(".tool-card.running")).toBeTruthy();
  });
});
