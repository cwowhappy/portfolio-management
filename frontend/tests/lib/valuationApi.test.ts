import { describe, it, expect, vi } from "vitest";
import { fetchValuationOverview } from "@/lib/valuationApi";

const OVERVIEW = {
  latestSnapshot: { tradingDay: "2026-08-27", peMedian: 19.14, pbMedian: 1.68, netBreakerCount: 220, netBreakerRatio: 0.041 },
  pePercentile: 100.0, pbPercentile: 100.0, netBreakerPercentile: 100.0,
  erp: 0.14, erpPercentile: null, thermometer: 80,
  indices: [{ indexCode: "000300", indexName: "沪深300", pe: 12.8, pb: 1.42, dividendYield: 2.35, pePercentile: 50.0, pbPercentile: 40.0 }],
  dataAccumulating: true,
};

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
});
