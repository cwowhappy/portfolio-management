import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import TrendChart from "@/components/valuation/TrendChart";
import type { ValuationSnapshot, IndexValuationSeries } from "@/lib/types";

// jsdom 下 ResponsiveContainer 测不到尺寸、渲染不出 svg，
// 因此 mock recharts 组件，聚焦数据映射断言。
const { LineChartMock } = vi.hoisted(() => ({
  LineChartMock: vi.fn((_props: { data: { day: string; pe: number | null; pb: number | null }[] }) => null),
}));

vi.mock("recharts", () => ({
  ResponsiveContainer: ({ children }: { children: ReactNode }) => (
    <div data-testid="trend-chart">{children}</div>
  ),
  LineChart: LineChartMock,
  Line: () => null,
  XAxis: () => null,
  YAxis: () => null,
  Tooltip: () => null,
  CartesianGrid: () => null,
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

describe("TrendChart", () => {
  afterEach(() => {
    cleanup();
  });

  beforeEach(() => {
    LineChartMock.mockClear();
  });

  it("空数据渲染积累中且不渲染图表", () => {
    render(<TrendChart snapshots={[]} />);
    expect(screen.getByText(/积累中/)).toBeTruthy();
    expect(LineChartMock).not.toHaveBeenCalled();
  });

  it("有数据时将快照映射为 day/pe/pb 并交给折线图", () => {
    render(<TrendChart snapshots={snapshots} />);
    expect(screen.getByText("估值历史走势")).toBeTruthy();
    expect(LineChartMock).toHaveBeenCalledTimes(1);
    const props = LineChartMock.mock.calls[0][0];
    expect(props.data).toEqual([
      { day: "2026-08-01", pe: 15, pb: 1.5 },
      { day: "2026-08-02", pe: 16, pb: 1.6 },
    ]);
  });

  it("选中指数时按 indexCode 过滤并按 tradingDay 升序渲染该指数序列", () => {
    render(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="000300" />);
    expect(screen.getByText("估值历史走势")).toBeTruthy();
    expect(LineChartMock).toHaveBeenCalledTimes(1);
    const props = LineChartMock.mock.calls[0][0];
    expect(props.data).toEqual([
      { day: "2026-08-01", pe: 12, pb: 1.2 },
      { day: "2026-08-02", pe: 13, pb: 1.3 },
    ]);
  });

  it("切换 selectedIndex 时渲染数据随之改变", () => {
    const { rerender } = render(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="000300" />);
    rerender(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="000905" />);
    const lastCall = LineChartMock.mock.calls.at(-1);
    expect(lastCall?.[0].data).toEqual([
      { day: "2026-08-01", pe: 22, pb: 1.8 },
      { day: "2026-08-02", pe: 23, pb: 1.9 },
    ]);
  });

  it("选中无数据序列的指数时渲染积累中且不渲染图表", () => {
    render(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="399006" />);
    expect(screen.getByText(/积累中/)).toBeTruthy();
    expect(LineChartMock).not.toHaveBeenCalled();
  });
});
