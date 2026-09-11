import { describe, it, expect } from "vitest";
import { KlineParamsSchema, ValuationParamsSchema, OverviewParamsSchema } from "@/lib/tool-params";

describe("tool-params（useRenderTool parameters 用的入参 schema）", () => {
  it("kline：code 必填，period/limit 可选", () => {
    expect(KlineParamsSchema.safeParse({ code: "600519" }).success).toBe(true);
    expect(KlineParamsSchema.safeParse({ code: "600519", period: "day", limit: 500 }).success).toBe(true);
    expect(KlineParamsSchema.safeParse({ period: "day" }).success).toBe(false);
  });
  it("valuation/overview 无必填参数", () => {
    expect(ValuationParamsSchema.safeParse({}).success).toBe(true);
    expect(OverviewParamsSchema.safeParse({}).success).toBe(true);
  });
});
