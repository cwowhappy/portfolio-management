import { describe, it, expect, vi } from "vitest";
import { fetchScreenedStocks } from "@/lib/screeningApi";

const STOCKS = [
  { stockCode: "601398", stockName: "工商银行", industryCode: "801780", industryName: "银行",
    peTtm: 5.6, pb: 0.62, dividendYield: 5.4, roe: 11.8, roa: 0.95, grossMargin: 0,
    debtToAssets: 91.8, currentRatio: 0.9, revenueYoy: 2.1, netprofitYoy: 1.8,
    totalMv: 2200000000000, turnoverRate: 0.18 },
];

describe("screeningApi", () => {
  it("fetchScreenedStocks 解析正常响应", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => STOCKS }));
    const data = await fetchScreenedStocks({ peTtmMax: 20, roeMin: 15 });
    expect(data[0].stockCode).toBe("601398");
    expect(data[0].peTtm).toBe(5.6);
  });

  it("totalMvMin 亿元换算为元", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => STOCKS });
    vi.stubGlobal("fetch", fetchMock);
    await fetchScreenedStocks({ totalMvMin: 100 });
    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain("totalMvMin=10000000000"); // 100 亿 = 1e10 元
  });

  it("非 2xx 抛错", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 400 }));
    await expect(fetchScreenedStocks({ peTtmMax: 20 })).rejects.toThrow();
  });
});
