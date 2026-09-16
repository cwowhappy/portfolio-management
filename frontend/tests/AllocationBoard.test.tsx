import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import AllocationBoard from "@/components/allocation/AllocationBoard";
import * as allocationApi from "@/lib/allocationApi";
import type { PlanView } from "@/lib/types";

vi.mock("@/lib/allocationApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/allocationApi")>("@/lib/allocationApi");
  return {
    ...actual,
    fetchTemplates: vi.fn(),
    fetchPlans: vi.fn(),
    fetchDeviation: vi.fn(),
    createPlan: vi.fn(),
    updatePlan: vi.fn(),
    activatePlan: vi.fn(),
    deletePlan: vi.fn(),
    fetchQuestionnaire: vi.fn(),
    fetchAssessment: vi.fn(),
    submitAssessment: vi.fn(),
    fetchRebalance: vi.fn(),
    ackRebalance: vi.fn(),
  };
});

const api = vi.mocked(allocationApi);

const plan = (id: number, name: string): PlanView => ({
  id, name, source: "CUSTOM", weights: [], active: false, rebalanceFrequency: "OFF", lastRebalancedAt: null,
});

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchTemplates.mockResolvedValue([]);
  api.fetchPlans.mockResolvedValue([plan(1, "平衡"), plan(2, "激进")]);
  api.fetchDeviation.mockResolvedValue({ slices: [] });
  api.fetchAssessment.mockResolvedValue(undefined);
  api.fetchRebalance.mockResolvedValue({ hasActivePlan: false, totalAssets: 0, suppressed: false, anyAlert: false, items: [], timeTrigger: null });
  api.activatePlan.mockResolvedValue(plan(1, "平衡"));
});

afterEach(() => {
  cleanup();
});

describe("AllocationBoard", () => {
  it("渲染标题与方案列表", async () => {
    render(<AllocationBoard />);
    expect(await screen.findByText("资产配置")).toBeTruthy();
    expect(await screen.findByText("平衡")).toBeTruthy();
    expect(screen.getByText("激进")).toBeTruthy();
  });

  it("渲染再平衡卡（空方案态；偏离图同文案故允许多处）", async () => {
    render(<AllocationBoard />);
    const hits = await screen.findAllByText(/暂无生效方案/);
    expect(hits.length).toBeGreaterThanOrEqual(2); // RebalanceCard + DeviationChart
  });

  it("提醒态渲染再平衡横幅与建议表", async () => {
    api.fetchRebalance.mockResolvedValue({
      hasActivePlan: true, totalAssets: 10000, suppressed: false, anyAlert: true,
      items: [{ assetClass: "STOCK", targetWeight: 25, actualWeight: 0, deviation: -25,
        targetAmount: 2500, currentAmount: 0, suggestedAmount: 2500, thresholdBreached: true }],
      timeTrigger: { frequency: "OFF", anchorDate: null, dueDate: null, daysOverdue: 0, triggered: false },
    });
    render(<AllocationBoard />);
    expect(await screen.findByTestId("rebalance-alert")).toBeTruthy();
    expect(screen.getByTestId("rebalance-row-STOCK").textContent).toContain("2,500");
  });

  it("reload 竞态：丢弃过期响应，只保留最新一次加载结果", async () => {
    let resolveStale!: (v: PlanView[]) => void;
    const staleGate = new Promise<PlanView[]>((res) => { resolveStale = res; });
    api.fetchPlans.mockResolvedValueOnce([plan(1, "平衡"), plan(2, "激进")]); // 挂载
    api.fetchPlans.mockReturnValueOnce(staleGate); // reload #2（旧，挂起）
    api.fetchPlans.mockResolvedValueOnce([plan(3, "FRESH")]); // reload #3（新）

    render(<AllocationBoard />);
    await screen.findByText("平衡");
    expect(api.fetchPlans).toHaveBeenCalledTimes(1);

    // 触发 reload #2
    fireEvent.click(screen.getAllByRole("button", { name: "设为生效" })[0]);
    await vi.waitFor(() => expect(api.fetchPlans).toHaveBeenCalledTimes(2));

    // 触发 reload #3（立即返回 FRESH）
    fireEvent.click(screen.getAllByRole("button", { name: "设为生效" })[0]);
    await vi.waitFor(() => expect(api.fetchPlans).toHaveBeenCalledTimes(3));
    expect(await screen.findByText("FRESH")).toBeTruthy();

    // 放行过期 reload #2：守卫应丢弃，不被覆盖成 STALE
    await act(async () => { resolveStale([plan(9, "STALE")]); });
    await vi.waitFor(() => expect(api.fetchPlans).toHaveBeenCalledTimes(3));
    expect(screen.queryByText("STALE")).toBeNull();
    expect(screen.getByText("FRESH")).toBeTruthy();
  });
});
