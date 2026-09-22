import { describe, expect, it } from "vitest";
import { IndustryBoardItemSchema, IndustryStockSchema } from "@/lib/schemas";

describe("industry schemas", () => {
  it("parses board item with nullable percentile/prosperity", () => {
    const item = IndustryBoardItemSchema.parse({
      industryCode: "801780", industryName: "银行",
      pe: 5.5, pb: 0.8, roe: null, dividendYield: null,
      pePercentile: 40.0, pbPercentile: null,
      prosperity: "UP",
      prosperityInputs: { roeDeltaMedian: 1.0, revenueYoyMedian: 10.0, sampleSize: 42 },
    });
    expect(item.prosperity).toBe("UP");
    expect(item.pbPercentile).toBeNull();
  });

  it("parses stock with revenue report date", () => {
    const s = IndustryStockSchema.parse({
      stockCode: "601398", stockName: "工商银行", totalMv: 2e12, revenue: 4e11,
      revenueReportDate: "2025-12-31", roe: 11, peTtm: 6, pb: 0.6, dividendYield: 5, prosperity: null,
    });
    expect(s.revenueReportDate).toBe("2025-12-31");
  });

  it("rejects bad prosperity", () => {
    expect(() => IndustryStockSchema.parse({
      stockCode: "x", stockName: "x", totalMv: null, revenue: null, revenueReportDate: null,
      roe: null, peTtm: null, pb: null, dividendYield: null, prosperity: "SIDEWAYS",
    })).toThrow();
  });
});
