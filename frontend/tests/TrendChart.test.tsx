import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import TrendChart from "@/components/valuation/TrendChart";
import type { ValuationSnapshot, IndexValuationSeries } from "@/lib/types";

// 记录 EChart 收到的 option 引用（mock 每渲染 push 一次），供 memo 稳定性用例断言引用不变
const seenOptions = vi.hoisted(() => [] as unknown[]);
vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => {
    seenOptions.push(p.option);
    return <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />;
  },
}));

const snapshots: ValuationSnapshot[] = [
  { tradingDay: "2026-08-01", peMedian: 15, pbMedian: 1.5, netBreakerCount: 1, netBreakerRatio: 0.1 },
  { tradingDay: "2026-08-02", peMedian: 16, pbMedian: 1.6, netBreakerCount: 1, netBreakerRatio: 0.1 },
];

// 含多指数，且 000300 的序列故意乱序（验证按 tradingDay 升序排序）。
const indexValuations: IndexValuationSeries[] = [
  { tradingDay: "2026-08-02", indexCode: "000300", indexName: "沪深300", pe: 13, pb: 1.3, dividendYield: 2.4 },
  { tradingDay: "2026-08-01", indexCode: "000300", indexName: "沪深300", pe: 12, pb: 1.2, dividendYield: 2.5 },
  { tradingDay: "2026-08-01", indexCode: "000905", indexName: "中证500", pe: 22, pb: 1.8, dividendYield: 1.5 },
  { tradingDay: "2026-08-02", indexCode: "000905", indexName: "中证500", pe: 23, pb: 1.9, dividendYield: 1.4 },
];

function readOption() {
  return JSON.parse(screen.getByTestId("trend-chart").dataset.option!);
}

afterEach(() => cleanup());

describe("TrendChart", () => {
  it("空数据渲染积累中且不渲染图表", () => {
    render(<TrendChart snapshots={[]} />);
    expect(screen.getByText(/积累中/)).toBeTruthy();
    expect(screen.queryByTestId("trend-chart")).toBeNull();
  });

  it("有数据时快照映射为双系列（PE/PB）折线 option", () => {
    render(<TrendChart snapshots={snapshots} />);
    expect(screen.getByText("估值历史走势")).toBeTruthy();
    const option = readOption();
    expect(option.xAxis.data).toEqual(["2026-08-01", "2026-08-02"]);
    expect(option.series.map((s: { name: string }) => s.name)).toEqual(["PE", "PB"]);
    expect(option.series[0].data).toEqual([15, 16]);
    expect(option.series[1].data).toEqual([1.5, 1.6]);
  });

  it("选中指数时按 indexCode 过滤并按 tradingDay 升序", () => {
    render(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="000300" />);
    const option = readOption();
    expect(option.xAxis.data).toEqual(["2026-08-01", "2026-08-02"]);
    expect(option.series[0].data).toEqual([12, 13]);
    expect(option.series[1].data).toEqual([1.2, 1.3]);
  });

  it("切换 selectedIndex 时渲染数据随之改变", () => {
    const { rerender } = render(
      <TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="000300" />,
    );
    rerender(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="000905" />);
    const option = readOption();
    expect(option.series[0].data).toEqual([22, 23]);
    expect(option.series[1].data).toEqual([1.8, 1.9]);
  });

  it("同 props 重渲染 option 引用稳定（data memo 不因 toPoints 每渲染新数组而失效）", () => {
    seenOptions.length = 0;
    // indexValuations 不传：走参数默认值的最坏情形，data 计算必须仍被 memo 钉住
    const view = render(<TrendChart snapshots={snapshots} />);
    view.rerender(<TrendChart snapshots={snapshots} />);
    view.rerender(<TrendChart snapshots={snapshots} />);
    expect(seenOptions.length).toBe(3); // 组件本体每次重渲染，EChart 均收到 option
    expect(seenOptions[0]).toBe(seenOptions[1]);
    expect(seenOptions[1]).toBe(seenOptions[2]);
  });

  it("选中无数据序列的指数时渲染积累中且不渲染图表", () => {
    render(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="399006" />);
    expect(screen.getByText(/积累中/)).toBeTruthy();
    expect(screen.queryByTestId("trend-chart")).toBeNull();
  });
});
