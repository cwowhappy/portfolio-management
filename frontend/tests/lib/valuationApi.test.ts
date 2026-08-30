import { afterEach, describe, it, expect, vi } from "vitest";
import { fetchValuationHistory, fetchValuationIndustries, fetchValuationOverview } from "@/lib/valuationApi";

const OVERVIEW = {
  latestSnapshot: { tradingDay: "2026-08-27", peMedian: 19.14, pbMedian: 1.68, netBreakerCount: 220, netBreakerRatio: 0.041 },
  pePercentile: 100.0, pbPercentile: 100.0, netBreakerPercentile: 100.0,
  erp: 0.14, erpPercentile: null, thermometer: 80,
  indices: [{ indexCode: "000300", indexName: "沪深300", pe: 12.8, pb: 1.42, dividendYield: 2.35, pePercentile: 50.0, pbPercentile: 40.0 }],
  dataAccumulating: true,
};

const HISTORY = {
  snapshots: [
    { tradingDay: "2026-08-26", peMedian: 19.0, pbMedian: 1.66, netBreakerCount: 215, netBreakerRatio: 0.04 },
    { tradingDay: "2026-08-27", peMedian: 19.14, pbMedian: 1.68, netBreakerCount: 220, netBreakerRatio: 0.041 },
  ],
  treasuryYields: [{ tradingDay: "2026-08-27", yield10y: 1.78 }],
  indexValuations: [{ tradingDay: "2026-08-27", indexCode: "000300", indexName: "沪深300", pe: 12.8, pb: 1.42, dividendYield: 2.35 }],
};

const INDUSTRIES = [
  { industryCode: "801120", industryName: "食品饮料", pe: 22.5, pb: 4.1, roe: 18.2, dividendYield: 2.1 },
  { industryCode: "801780", industryName: "银行", pe: 6.1, pb: 0.62, roe: 10.1, dividendYield: 4.8 },
];

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("valuationApi", () => {
  it("fetchValuationOverview 解析正常响应", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true,
      json: async () => OVERVIEW,
    }));

    const data = await fetchValuationOverview();
    expect(data.latestSnapshot?.peMedian).toBe(19.14);
    expect(data.dataAccumulating).toBe(true);
  });

  it("非 2xx 抛错", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(fetchValuationOverview()).rejects.toThrow();
  });

  it("fetchValuationHistory 请求正确路径并解析响应", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => HISTORY });
    vi.stubGlobal("fetch", fetchMock);

    const data = await fetchValuationHistory();
    expect(fetchMock).toHaveBeenCalledWith("/api/valuation/history", expect.objectContaining({ cache: "no-store" }));
    expect(data.snapshots).toHaveLength(2);
    expect(data.snapshots[1].peMedian).toBe(19.14);
    expect(data.treasuryYields[0].yield10y).toBe(1.78);
    expect(data.indexValuations[0].indexCode).toBe("000300");
  });

  it("fetchValuationHistory 非 2xx 抛错", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 502 }));
    await expect(fetchValuationHistory()).rejects.toThrow("请求失败 (502)");
  });

  it("fetchValuationHistory 响应格式非法时报数据格式异常", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => ({ snapshots: "oops" }) }));
    await expect(fetchValuationHistory()).rejects.toThrow("数据格式异常");
  });

  it("fetchValuationIndustries 默认按 pe 排序并解析数组", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => INDUSTRIES });
    vi.stubGlobal("fetch", fetchMock);

    const data = await fetchValuationIndustries();
    expect(fetchMock).toHaveBeenCalledWith("/api/valuation/industries?sort=pe", expect.anything());
    expect(data).toHaveLength(2);
    expect(data[0].industryName).toBe("食品饮料");
  });

  it("fetchValuationIndustries 透传自定义排序参数", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => INDUSTRIES });
    vi.stubGlobal("fetch", fetchMock);

    await fetchValuationIndustries("pb");
    expect(fetchMock).toHaveBeenCalledWith("/api/valuation/industries?sort=pb", expect.anything());
  });

  it("fetchValuationIndustries 非 2xx 抛错", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 500 }));
    await expect(fetchValuationIndustries()).rejects.toThrow("请求失败 (500)");
  });

  it("fetchValuationIndustries 响应格式非法时报数据格式异常", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, json: async () => [{ industryCode: 123 }] }));
    await expect(fetchValuationIndustries()).rejects.toThrow("数据格式异常");
  });
});
