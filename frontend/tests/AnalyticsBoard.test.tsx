import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AnalyticsBoard from "@/components/analytics/AnalyticsBoard";
import * as analyticsApi from "@/lib/analyticsApi";

// jsdom 无 canvas：mock EChart 壳，经 data-option 断言传入的 option（模式对齐 AllocationPie/TrendChart 测试）
vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

vi.mock("@/lib/analyticsApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/analyticsApi")>("@/lib/analyticsApi");
  return { ...actual, fetchOverview: vi.fn(), fetchNav: vi.fn(), fetchAnnual: vi.fn(), fetchTradeStats: vi.fn() };
});
const api = vi.mocked(analyticsApi);

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchOverview.mockResolvedValue({
    totalValue: 1200, twrCumulative: 0.2, twrAnnualized: 0.44, irr: null, windowDays: 2,
    benchmarks: { "000300": { indexCode: "000300", indexName: "沪深300", twr: 0.05, excess: 0.15 } },
  });
  api.fetchNav.mockResolvedValue({
    windowStart: "2026-01-05", windowEnd: "2026-01-07",
    points: [{ date: "2026-01-05", totalValue: 1000 }, { date: "2026-01-06", totalValue: 1100 }, { date: "2026-01-07", totalValue: 1200 }],
    benchmarks: { "000300": [{ date: "2026-01-05", close: 3900 }, { date: "2026-01-06", close: 3950 }, { date: "2026-01-07", close: 4000 }] },
  });
  api.fetchAnnual.mockResolvedValue([{ year: 2026, portfolioTwr: 0.2, benchmarkTwr: { "000300": 0.05 }, excess: { "000300": 0.15 } }]);
  api.fetchTradeStats.mockResolvedValue({ sellCount: 2, winCount: 1, winRate: "0.5", avgWin: "500", avgLoss: "200",
    profitFactor: "2.5", avgHoldingDays: "55", bestPnl: "500", worstPnl: "-200" });
});
afterEach(cleanup);

function readNavOption() {
  return JSON.parse(screen.getByTestId("nav-chart").dataset.option!);
}

describe("AnalyticsBoard", () => {
  it("渲染四块：总览卡/净值图/年度表/交易统计", async () => {
    render(<AnalyticsBoard />);
    await waitFor(() => expect(screen.getByTestId("analytics-overview")).toBeTruthy());
    expect(screen.getByTestId("nav-chart")).toBeTruthy();
    expect(screen.getByTestId("annual-table")).toBeTruthy();
    expect(screen.getByTestId("trade-stats")).toBeTruthy();
    // TWR 累计/总资产断言圈定在总览块：年度表组合列同为 20.00%，不圈定会撞多匹配
    expect(within(screen.getByTestId("analytics-overview")).getByText(/20\.00%/)).toBeTruthy();
    expect(within(screen.getByTestId("analytics-overview")).getByText(/1,200/)).toBeTruthy();
    expect(within(screen.getByTestId("annual-table")).getByText(/2026/)).toBeTruthy(); // 年度表年份行
    expect(screen.getByTestId("analytics-overview").textContent).toContain("—"); // irr null → 「—」
  });

  it("盈亏比为 null 时显示「—」", async () => {
    api.fetchTradeStats.mockResolvedValue({ sellCount: 0, winCount: 0, winRate: "0", avgWin: "0", avgLoss: "0",
      profitFactor: null, avgHoldingDays: "0", bestPnl: "0", worstPnl: "0" });
    render(<AnalyticsBoard />);
    await waitFor(() => expect(screen.getByTestId("trade-stats").textContent).toContain("—"));
  });

  it("overview 204（undefined）时整页渲染 analytics-empty 空态", async () => {
    api.fetchOverview.mockResolvedValue(undefined);
    render(<AnalyticsBoard />);
    await waitFor(() => expect(screen.getByTestId("analytics-empty")).toBeTruthy());
    expect(screen.queryByTestId("analytics-overview")).toBeNull();
    expect(screen.queryByTestId("nav-chart")).toBeNull();
  });

  it("归一化：组合与基准各自除以首值 ×1000，首日恒为 1000", async () => {
    render(<AnalyticsBoard />);
    await waitFor(() => expect(screen.getByTestId("nav-chart")).toBeTruthy());
    const option = readNavOption();
    expect(option.series.map((s: { name: string }) => s.name)).toEqual(["组合", "沪深300"]);
    expect(option.series[0].data).toEqual([1000, 1100, 1200]);
    expect(option.series[1].data[0]).toBe(1000);
    expect(option.series[1].data[2]).toBeCloseTo((4000 / 3900) * 1000, 6);
  });

  it("基准窗口不同时按日期交集截齐对齐；无重叠的基准剔除而不清空整图", async () => {
    api.fetchNav.mockResolvedValue({
      windowStart: "2026-01-05", windowEnd: "2026-01-07",
      points: [{ date: "2026-01-05", totalValue: 1000 }, { date: "2026-01-06", totalValue: 1100 }, { date: "2026-01-07", totalValue: 1200 }],
      benchmarks: {
        // 000300 缺 01-06 → 共同日期截齐为 [01-05, 01-07]
        "000300": [{ date: "2026-01-05", close: 3900 }, { date: "2026-01-07", close: 4000 }],
        // 000905 与组合窗口完全无重叠 → 剔除
        "000905": [{ date: "2025-12-31", close: 5000 }],
      },
    });
    render(<AnalyticsBoard />);
    await waitFor(() => expect(screen.getByTestId("nav-chart")).toBeTruthy());
    const option = readNavOption();
    expect(option.xAxis.data).toEqual(["2026-01-05", "2026-01-07"]);
    expect(option.series.map((s: { name: string }) => s.name)).toEqual(["组合", "沪深300"]);
    expect(option.series[0].data).toEqual([1000, 1200]);
  });
});
