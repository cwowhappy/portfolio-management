import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import BacktestCard from "@/components/allocation/BacktestCard";
import * as allocationApi from "@/lib/allocationApi";
import type { BacktestView, PlanView, TemplateView } from "@/lib/types";

vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

vi.mock("@/lib/allocationApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/allocationApi")>("@/lib/allocationApi");
  return { ...actual, fetchPlans: vi.fn(), fetchTemplates: vi.fn(), fetchBacktest: vi.fn() };
});
const api = vi.mocked(allocationApi);

const plan = (id: number, name: string): PlanView => ({
  id, name, source: "CUSTOM", weights: [], active: false, rebalanceFrequency: "OFF", lastRebalancedAt: null,
});
const template: TemplateView = { id: "all-weather", name: "全天候", weights: [] };

// 服务层真实产出形态（Task 14 契约）：数值全 toPlainString 字符串——
// 曲线净值期初 1000；年化/MDD 为小数（×100 展示）；夏普为比率；rfFallback=退化口径标注。
const fixture: BacktestView = {
  planName: "永久组合", windowStart: "2021-09-24", windowEnd: "2026-09-24",
  window: "5Y", rebalance: "annual",
  curve: [{ date: "2021-09-24", value: "1000" }, { date: "2026-09-24", value: "1310.5" }],
  annualizedReturn: "0.0555", mdd: "0.12", sharpe: "0.85", rfFallback: false,
};

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchPlans.mockResolvedValue([plan(3, "永久组合"), plan(4, "二八再平衡")]);
  api.fetchTemplates.mockResolvedValue([template]);
});
afterEach(cleanup);

describe("BacktestCard", () => {
  it("默认参数跑回测：卡片内三指标（年化/MDD 百分比、夏普比率）与净值曲线图", async () => {
    api.fetchBacktest.mockResolvedValue(fixture);
    render(<BacktestCard />);
    fireEvent.click(await screen.findByRole("button", { name: "运行回测" }));
    await waitFor(() => expect(api.fetchBacktest).toHaveBeenCalledWith({ window: "5Y", rebalance: "never" }));
    const card = screen.getByTestId("backtest-card");
    expect(card.textContent).toContain("5.55%");
    expect(card.textContent).toContain("12.00%");
    expect(card.textContent).toContain("0.85");
    // 单序列折线：categories=curve 日期、series=净值数值
    const option = JSON.parse(screen.getByTestId("backtest-chart").dataset.option!);
    expect(option.xAxis.data).toEqual(["2021-09-24", "2026-09-24"]);
    expect(option.series[0].data).toEqual([1000, 1310.5]);
    expect(option.series[0].name).toBe("永久组合");
    expect(screen.queryByText(/rf=0/)).toBeNull(); // rfFallback=false 不标注退化口径
  });

  it("下拉取数填方案/模板选项，选择后按所选 planId/窗口/再平衡请求", async () => {
    api.fetchBacktest.mockResolvedValue(fixture);
    render(<BacktestCard />);
    await screen.findByText("二八再平衡"); // 下拉选项已加载
    fireEvent.change(screen.getByLabelText("方案"), { target: { value: "3" } });
    fireEvent.change(screen.getByLabelText("窗口"), { target: { value: "3Y" } });
    fireEvent.change(screen.getByLabelText("再平衡"), { target: { value: "annual" } });
    fireEvent.click(screen.getByRole("button", { name: "运行回测" }));
    await waitFor(() => expect(api.fetchBacktest).toHaveBeenCalledWith({ planId: 3, window: "3Y", rebalance: "annual" }));
  });

  it("不选方案时按模板回测：template 传参、planId 缺省", async () => {
    api.fetchBacktest.mockResolvedValue(fixture);
    render(<BacktestCard />);
    await screen.findByText("全天候");
    fireEvent.change(screen.getByLabelText("模板"), { target: { value: "all-weather" } });
    fireEvent.click(screen.getByRole("button", { name: "运行回测" }));
    await waitFor(() => expect(api.fetchBacktest).toHaveBeenCalledWith({ template: "all-weather", window: "5Y", rebalance: "never" }));
  });

  it("REITs 422：展示后端错误文案，卡片不崩、无图表", async () => {
    // http 层既有抛法：非 2xx 取响应体 message 包 Error
    api.fetchBacktest.mockRejectedValue(new Error("REITs 无回测数据源，请将 REITs 权重置 0 后重试"));
    render(<BacktestCard />);
    fireEvent.click(await screen.findByRole("button", { name: "运行回测" }));
    expect(await screen.findByText("REITs 无回测数据源，请将 REITs 权重置 0 后重试")).toBeTruthy();
    expect(screen.getByTestId("backtest-card")).toBeTruthy();
    expect(screen.queryByTestId("backtest-chart")).toBeNull();
  });

  it("sharpe 不可算为「—」，rfFallback=true 标注 rf=0 退化口径", async () => {
    api.fetchBacktest.mockResolvedValue({ ...fixture, sharpe: null, rfFallback: true });
    render(<BacktestCard />);
    fireEvent.click(await screen.findByRole("button", { name: "运行回测" }));
    await screen.findByTestId("backtest-chart");
    const card = screen.getByTestId("backtest-card");
    expect(card.textContent).toContain("—");
    expect(screen.getByText(/rf=0 口径/)).toBeTruthy();
  });
});
