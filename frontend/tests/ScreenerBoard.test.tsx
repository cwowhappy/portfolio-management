import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ScreenerBoard from "@/components/screening/ScreenerBoard";
import { useAuth } from "@/lib/auth";
import * as screeningApi from "@/lib/screeningApi";
import * as watchlistApi from "@/lib/watchlistApi";
import * as valuationApi from "@/lib/valuationApi";
import type { ScreeningStock } from "@/lib/types";

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(),
  useRouter: () => ({ push: pushMock }),
}));
const pushMock = vi.fn();

vi.mock("@/lib/auth", () => ({ useAuth: vi.fn() }));
vi.mock("@/lib/valuationApi", () => ({ fetchValuationIndustries: vi.fn().mockResolvedValue([]) }));
vi.mock("@/lib/screeningApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/screeningApi")>()),
  fetchScreenedStocks: vi.fn(),
}));
vi.mock("@/lib/watchlistApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/watchlistApi")>()),
  fetchWatchlist: vi.fn().mockResolvedValue([]),
  addToWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
  searchStocks: vi.fn(),
}));
const screening = vi.mocked(screeningApi);
const watchlist = vi.mocked(watchlistApi);

const STOCK: ScreeningStock = {
  stockCode: "601398", stockName: "工商银行", industryCode: "801780", industryName: "银行",
  peTtm: 5.6, pb: 0.62, dividendYield: 5.4, roe: 11.8, roa: 0.95, grossMargin: 0,
  debtToAssets: 91.8, currentRatio: 0.9, revenueYoy: 2.1, netprofitYoy: 1.8,
  totalMv: 2.2e12, turnoverRate: 0.18,
};

const userStub = { id: 1, username: "u", role: "USER", status: "APPROVED", enabled: true } as NonNullable<ReturnType<typeof useAuth>["user"]>;

/** 触发筛选提交：先填 PE 条件（空条件会被拦），再走 form submit（tab 与提交按钮同名「筛选」）。 */
function submitForm() {
  fireEvent.change(screen.getByPlaceholderText("如 20"), { target: { value: "20" } });
  fireEvent.submit(screen.getByPlaceholderText("如 20").closest("form")!);
}

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(useAuth).mockReturnValue({ user: null, loading: false } as ReturnType<typeof useAuth>);
  screening.fetchScreenedStocks.mockResolvedValue([STOCK]);
});
afterEach(cleanup);

describe("ScreenerBoard", () => {
  it("未登录点 ⭐ 跳登录", async () => {
    render(<ScreenerBoard />);
    submitForm();
    const star = await screen.findByRole("button", { name: "加自选 601398" });
    fireEvent.click(star);
    expect(pushMock).toHaveBeenCalledWith("/login?redirect=/screener");
    expect(watchlist.addToWatchlist).not.toHaveBeenCalled();
  });

  it("已登录点 ⭐ 调 addToWatchlist 并置实心", async () => {
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    watchlist.addToWatchlist.mockResolvedValue(undefined);

    render(<ScreenerBoard />);
    submitForm();
    const star = await screen.findByRole("button", { name: "加自选 601398" });
    fireEvent.click(star);

    await waitFor(() => expect(watchlist.addToWatchlist).toHaveBeenCalledWith("601398"));
    await waitFor(() => expect(screen.getByRole("button", { name: "移除自选 601398" })).toBeTruthy());
  });

  it("导出 <a> 的 href 携带当前筛选参数且带 download 属性", async () => {
    render(<ScreenerBoard />);
    submitForm();
    const link = await screen.findByTestId("export-csv");
    expect(link.getAttribute("href")).toContain("peTtmMax=20");
    expect(link.getAttribute("download")).not.toBeNull();
  });

  it("指数范围下拉进入筛选参数", async () => {
    render(<ScreenerBoard />);
    fireEvent.change(screen.getByLabelText("指数范围"), { target: { value: "000300" } });
    submitForm();
    await screen.findByRole("button", { name: "加自选 601398" });
    const arg = screening.fetchScreenedStocks.mock.lastCall?.[0] as ScreeningStock & { indexCode?: string };
    expect(arg.indexCode).toBe("000300");
  });

  it("自选 tab 切换渲染面板", async () => {
    render(<ScreenerBoard />);
    fireEvent.click(screen.getByTestId("tab-watchlist"));
    expect(await screen.findByTestId("watchlist-panel")).toBeTruthy();
  });
});
