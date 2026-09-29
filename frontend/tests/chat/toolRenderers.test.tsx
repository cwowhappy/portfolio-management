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
  it("注册 4 既有 + 5 MS-12 + research_draft + search_news 具名渲染器（无 agentId，05 §4.1）", () => {
    render(<ChartToolRenderers />);
    expect(renderToolConfigs.map((c) => c.name)).toEqual(
      ["get_kline", "get_valuation", "get_market_overview", "get_financials",
        "screen_stocks", "analyze_financials", "analyze_industry", "suggest_allocation", "analyze_portfolio",
        "research_draft", "search_news"]);
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
});
