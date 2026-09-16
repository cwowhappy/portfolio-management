import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import WatchlistPanel from "@/components/screening/WatchlistPanel";
import * as watchlistApi from "@/lib/watchlistApi";
import type { StockSearchHit, WatchlistItemView } from "@/lib/types";

vi.mock("@/lib/watchlistApi", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/watchlistApi")>()),
  fetchWatchlist: vi.fn(),
  addToWatchlist: vi.fn(),
  removeFromWatchlist: vi.fn(),
  searchStocks: vi.fn(),
}));
const api = vi.mocked(watchlistApi);

const row = (code: string, price: number | null): WatchlistItemView => ({
  stockCode: code, stockName: `${code}名称`, industryName: "银行", price,
  peTtm: 5.6, pb: 0.62, dividendYield: 5.4, totalMv: 1e12, addedAt: "2026-09-16T00:00:00Z",
});

beforeEach(() => { vi.clearAllMocks(); api.fetchWatchlist.mockResolvedValue([]); });
afterEach(cleanup);

describe("WatchlistPanel", () => {
  it("渲染行；现价缺失显示 —", async () => {
    api.fetchWatchlist.mockResolvedValue([row("601398", 5.6), row("600519", null)]);
    render(<WatchlistPanel />);
    await waitFor(() => expect(screen.getByText("601398")).toBeTruthy());
    expect(screen.getByTestId("watchlist-price-601398").textContent).toBe("5.60");
    expect(screen.getByTestId("watchlist-price-600519").textContent).toBe("—");
  });

  it("搜索候选点选即添加并刷新列表", async () => {
    const hit: StockSearchHit = { stockCode: "601398", stockName: "工商银行", industryName: "银行", peTtm: 5.6, pb: 0.62, totalMv: 1e12 };
    api.searchStocks.mockResolvedValue([hit]);
    api.addToWatchlist.mockResolvedValue(undefined);
    api.fetchWatchlist.mockResolvedValue([]).mockResolvedValueOnce([]).mockResolvedValueOnce([row("601398", 5.6)]);

    render(<WatchlistPanel />);
    fireEvent.change(screen.getByPlaceholderText("搜索代码或名称添加"), { target: { value: "601" } });
    const option = await screen.findByRole("button", { name: /601398 工商银行/ });
    fireEvent.click(option);

    await waitFor(() => expect(api.addToWatchlist).toHaveBeenCalledWith("601398"));
    await waitFor(() => expect(api.fetchWatchlist).toHaveBeenCalledTimes(2)); // 挂载 + 添加后刷新
  });

  it("移除按钮调 removeFromWatchlist 并刷新", async () => {
    api.fetchWatchlist.mockResolvedValue([row("601398", 5.6)]);
    api.removeFromWatchlist.mockResolvedValue(undefined);
    render(<WatchlistPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "移除 601398" }));
    await waitFor(() => expect(api.removeFromWatchlist).toHaveBeenCalledWith("601398"));
    await waitFor(() => expect(api.fetchWatchlist).toHaveBeenCalledTimes(2));
  });

  it("匿名（authenticated=false）显示登录引导且不请求", async () => {
    render(<WatchlistPanel authenticated={false} />);
    expect(await screen.findByText(/登录后可查看自选/)).toBeTruthy();
    expect(api.fetchWatchlist).not.toHaveBeenCalled();
  });

  it("接口失败显示错误文案（不误判为未登录）", async () => {
    api.fetchWatchlist.mockRejectedValue(new Error("服务器开小差"));
    render(<WatchlistPanel />);
    expect(await screen.findByText(/服务器开小差/)).toBeTruthy();
  });

  it("空列表显示暂无自选", async () => {
    render(<WatchlistPanel />);
    expect(await screen.findByText(/暂无自选/)).toBeTruthy();
  });
});
