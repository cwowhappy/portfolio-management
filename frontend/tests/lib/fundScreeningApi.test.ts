import { describe, it, expect, vi } from "vitest";
import { buildFundExportHref, fetchFundScreening } from "@/lib/fundScreeningApi";

const FUNDS = [
  { fundCode: "510300", fundName: "沪深300ETF", feeRate: 0.5, scale: 120.3,
    trackingIndexName: "沪深300指数", category: "宽基", trackingError1y: 0.0318 },
];

describe("fundScreeningApi", () => {
  it("fetchFundScreening 解析正常响应并按后端口径直传参数（无换算）", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => FUNDS });
    vi.stubGlobal("fetch", fetchMock);
    const data = await fetchFundScreening({
      feeRateMax: 0.6, scaleMin: 10, trackingErrorMax: 0.05, category: "宽基",
      sortBy: "tracking_error_1y", sortDirection: "ASC", limit: 200,
    });
    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain("/api/screening/funds?");
    expect(url).toContain("feeRateMax=0.6"); // 百分数口径直传
    expect(url).toContain("scaleMin=10"); // 亿元口径直传
    expect(url).toContain("trackingErrorMax=0.05"); // 小数口径直传
    expect(url).toContain(`category=${encodeURIComponent("宽基")}`);
    expect(data[0].fundCode).toBe("510300");
    expect(data[0].trackingError1y).toBeCloseTo(0.0318);
  });

  it("undefined/空 参数从查询串省略", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => FUNDS });
    vi.stubGlobal("fetch", fetchMock);
    await fetchFundScreening({ feeRateMax: 0.6 });
    const url = fetchMock.mock.calls[0][0] as string;
    expect(url).toContain("feeRateMax=0.6");
    expect(url).not.toContain("scaleMin");
    expect(url).not.toContain("trackingErrorMax");
    expect(url).not.toContain("category");
  });

  it("非 2xx 抛错", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: false, status: 400 }));
    await expect(fetchFundScreening({ feeRateMax: 0.6 })).rejects.toThrow();
  });

  it("buildFundExportHref 与筛选同参生成导出 URL", () => {
    const href = buildFundExportHref({
      feeRateMax: 0.6, scaleMin: 10, category: "宽基",
      sortBy: "tracking_error_1y", sortDirection: "ASC", limit: 200,
    });
    expect(href).toBe(
      `/api/screening/funds/export?feeRateMax=0.6&scaleMin=10&category=${encodeURIComponent("宽基")}` +
      "&sortBy=tracking_error_1y&sortDirection=ASC&limit=200",
    );
  });
});
