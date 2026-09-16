import { afterEach, describe, expect, it, vi } from "vitest";
import { addToWatchlist, fetchWatchlist, removeFromWatchlist, searchStocks } from "@/lib/watchlistApi";

vi.stubGlobal("fetch", vi.fn());
const fetchMock = vi.mocked(fetch);

afterEach(() => vi.clearAllMocks());

const ROW = {
  stockCode: "601398", stockName: "工商银行", industryName: "银行", price: 5.6,
  peTtm: 5.6, pb: 0.62, dividendYield: 5.4, totalMv: 1e12, addedAt: "2026-09-16T00:00:00Z",
};

describe("watchlistApi", () => {
  it("fetchWatchlist 走反代 GET /api/watchlist 并 zod 校验", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([ROW]), { status: 200 }));
    const rows = await fetchWatchlist();
    expect(fetchMock).toHaveBeenCalledWith("/api/watchlist", expect.anything());
    expect(rows[0].stockCode).toBe("601398");
    expect(rows[0].price).toBe(5.6);
  });

  it("addToWatchlist POST JSON body；removeFromWatchlist DELETE 路径参数", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 })); // 后端幂等添加返回 204 无体
    await addToWatchlist("601398");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/watchlist");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body))).toEqual({ stockCode: "601398" });

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await removeFromWatchlist("601398");
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/watchlist/601398");
  });

  it("searchStocks 编码搜索词走公开筛选端点", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
    await searchStocks("茅台 600");
    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain("/api/screening/stocks/search?q=");
    expect(url).toContain(encodeURIComponent("茅台 600"));
  });
});
