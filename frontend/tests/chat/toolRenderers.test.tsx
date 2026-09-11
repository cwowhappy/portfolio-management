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
  it("注册 get_kline/get_valuation/get_market_overview/get_financials 四个具名渲染器（无 agentId，05 §4.1）", () => {
    render(<ChartToolRenderers />);
    expect(renderToolConfigs.map((c) => c.name)).toEqual(
      ["get_kline", "get_valuation", "get_market_overview", "get_financials"]);
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
});
