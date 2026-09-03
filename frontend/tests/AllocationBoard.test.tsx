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
  };
});

const api = vi.mocked(allocationApi);

const plan = (id: number, name: string): PlanView => ({
  id, name, source: "CUSTOM", weights: [], active: false,
});

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchTemplates.mockResolvedValue([]);
  api.fetchPlans.mockResolvedValue([plan(1, "平衡"), plan(2, "激进")]);
  api.fetchDeviation.mockResolvedValue({ slices: [] });
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
