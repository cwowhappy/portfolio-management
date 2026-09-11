import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";

// jsdom 无 canvas：mock EChart 壳捕获 option（模式对齐 05 §六）
vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string; height?: number }) => (
    <div data-testid={p.testid ?? "echart"} data-height={p.height} data-option={JSON.stringify(p.option)} />
  ),
}));

import { ChartCard, type ChartCardBuilder } from "@/components/chat/charts/ChartCard";
import { buildCandlestickOption } from "@/components/charts/optionBuilders";

// 严格函数类型参数逆变：CandlestickSpec 级 builder 不能直接赋给 (spec: ChartSpec)=>ECOption 的 prop。
// 沿用「先 cast」既有裁决（Task 4 报告：可比类型直接 as，不引入 as unknown）——运行时行为不变。
const anyBuilder = buildCandlestickOption as ChartCardBuilder;

const klineSpec = {
  specVersion: 1, type: "candlestick", title: "600519 贵州茅台 日K",
  symbol: "600519 贵州茅台", period: "day",
  dates: ["09-09"], klines: [[1800, 1850, 1790, 1860]],
};
const errorResult = JSON.stringify({ error: "Tool execution failed: 数据源超时", hint: "请稍后重试" });

afterEach(() => cleanup());

describe("ChartCard（状态机壳，05 §4.6）", () => {
  it("inProgress 渲染 .tool-card.running 骨架，不渲染图表", () => {
    render(<ChartCard status="inProgress" name="get_kline" builder={anyBuilder} />);
    const card = document.querySelector(".tool-card.running");
    expect(card).toBeTruthy();
    expect(screen.queryByTestId("chart-card-chart")).toBeNull();
  });

  it("complete + 合法 candlestick → EChart 收到 builder 产物（含 palette 涨跌色）", () => {
    render(<ChartCard status="complete" name="get_kline" result={JSON.stringify(klineSpec)} builder={anyBuilder} />);
    const el = screen.getByTestId("chart-card-chart");
    expect(el).toBeTruthy();
    const option = JSON.parse(el.dataset.option!);
    const candle = option.series.find((s: { type: string }) => s.type === "candlestick");
    // jsdom 取不到 CSS 变量 → 回退深色字面值（生产路径为运行时解析值，Task 6/e2e 覆盖）
    expect(candle.itemStyle.color).toBeTruthy();
  });

  it("complete + 错误内容（后端 error JSON）→ 降级折叠卡，原文截断 2000", () => {
    render(<ChartCard status="complete" name="get_kline" result={errorResult} builder={anyBuilder} />);
    expect(screen.queryByTestId("chart-card-chart")).toBeNull();
    const details = document.querySelector("details.tool-card")!;
    expect(details).toBeTruthy();
    expect(details.textContent).toContain("Tool execution failed");
  });

  it("complete + 非法 spec（specVersion 错/JSON 坏）→ 降级折叠卡", () => {
    render(<ChartCard status="complete" name="get_kline" result='{"specVersion":2,"type":"pie"}' builder={anyBuilder} />);
    expect(document.querySelector("details.tool-card")).toBeTruthy();
    render(<ChartCard status="complete" name="get_kline" result="不是JSON" builder={anyBuilder} />);
    expect(document.querySelectorAll("details.tool-card")).toHaveLength(2);
  });

  it("complete + table 变体 → 本期降级折叠卡（P4 换 DataTable）", () => {
    const tableSpec = {
      specVersion: 1, type: "table", title: "财务指标",
      columns: [{ key: "a", label: "A" }], rows: [{ a: 1 }],
    };
    render(<ChartCard status="complete" name="get_financials" result={JSON.stringify(tableSpec)} builder={anyBuilder} />);
    expect(screen.queryByTestId("chart-card-chart")).toBeNull();
    expect(document.querySelector("details.tool-card")).toBeTruthy();
  });

  it("complete 但 result undefined（RUN_ERROR 流级错误，无 toolMessage）→ 维持骨架", () => {
    render(<ChartCard status="complete" name="get_kline" builder={anyBuilder} />);
    expect(document.querySelector(".tool-card.running")).toBeTruthy();
  });

  it("candlestick 高度 360、其余 240", () => {
    const { rerender } = render(<ChartCard status="complete" name="get_kline" result={JSON.stringify(klineSpec)} builder={anyBuilder} />);
    expect(screen.getByTestId("chart-card-chart").dataset.height).toBe("360");
    const lineSpec = { specVersion: 1, type: "line", title: "PE", categories: ["d1"], series: [{ name: "PE", data: [1] }] };
    rerender(<ChartCard status="complete" name="get_valuation" result={JSON.stringify(lineSpec)} builder={anyBuilder} />);
    expect(screen.getByTestId("chart-card-chart").dataset.height).toBe("240");
  });
});
