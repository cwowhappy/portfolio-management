import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import AllocationAlertDot from "@/components/nav/AllocationAlertDot";
import { useAuth } from "@/lib/auth";
import * as allocationApi from "@/lib/allocationApi";

vi.mock("@/lib/auth", () => ({ useAuth: vi.fn() }));
vi.mock("@/lib/allocationApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/allocationApi")>()),
  fetchRebalance: vi.fn(),
}));
const api = vi.mocked(allocationApi);

const userStub = { id: 1, username: "u", role: "USER", status: "APPROVED", enabled: true } as ReturnType<typeof useAuth>["user"];

beforeEach(() => { vi.clearAllMocks(); });
afterEach(cleanup);

describe("AllocationAlertDot", () => {
  it("已登录且有提醒时渲染红点；rebalance-refresh 事件触发重拉", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    api.fetchRebalance.mockResolvedValue({
      hasActivePlan: true, totalAssets: 1, suppressed: false, anyAlert: true, items: [], timeTrigger: null,
    });

    render(<AllocationAlertDot />);
    await waitFor(() => expect(screen.getByTestId("allocation-alert-dot")).toBeTruthy());

    api.fetchRebalance.mockResolvedValue({
      hasActivePlan: false, totalAssets: 0, suppressed: false, anyAlert: false, items: [], timeTrigger: null,
    });
    window.dispatchEvent(new CustomEvent("rebalance-refresh"));
    await waitFor(() => expect(screen.queryByTestId("allocation-alert-dot")).toBeNull());
  });

  it("未登录不请求不渲染", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: null, loading: false } as ReturnType<typeof useAuth>);
    render(<AllocationAlertDot />);
    await new Promise((r) => setTimeout(r, 10));
    expect(api.fetchRebalance).not.toHaveBeenCalled();
    expect(screen.queryByTestId("allocation-alert-dot")).toBeNull();
  });

  it("请求失败静默不渲染", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    api.fetchRebalance.mockRejectedValue(new Error("请求失败"));
    render(<AllocationAlertDot />);
    await waitFor(() => expect(api.fetchRebalance).toHaveBeenCalled());
    expect(screen.queryByTestId("allocation-alert-dot")).toBeNull();
  });
});
