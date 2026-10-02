import { describe, it, expect } from "vitest";
import {
  KlineParamsSchema,
  ValuationParamsSchema,
  OverviewParamsSchema,
  SearchNewsParamsSchema,
  SearchAnnouncementsParamsSchema,
} from "@/lib/tool-params";

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
  it("search_news：七参数全可空，日期须 yyyy-MM-dd", () => {
    expect(SearchNewsParamsSchema.safeParse({}).success).toBe(true);
    expect(
      SearchNewsParamsSchema.safeParse({
        q: "回购",
        stock: "600519",
        industry: "801140",
        from: "2026-09-01",
        to: "2026-09-28",
        minImportance: 40,
        limit: 20,
      }).success,
    ).toBe(true);
  });
  it("search_news：日期格式非法被拒（yyyy-MM-dd 契约）", () => {
    expect(SearchNewsParamsSchema.safeParse({ from: "2026/09/01" }).success).toBe(false);
    expect(SearchNewsParamsSchema.safeParse({ to: "20260928" }).success).toBe(false);
  });
  it("search_announcements：七参数全可空，日期须 yyyy-MM-dd，scope 三枚举", () => {
    expect(SearchAnnouncementsParamsSchema.safeParse({}).success).toBe(true);
    expect(
      SearchAnnouncementsParamsSchema.safeParse({
        stock: "600519",
        type: "BUYBACK",
        from: "2026-09-01",
        to: "2026-09-28",
        q: "回购",
        scope: "holdings",
        limit: 20,
      }).success,
    ).toBe(true);
    expect(SearchAnnouncementsParamsSchema.safeParse({ scope: "mine" }).success).toBe(false);
  });
  it("search_announcements：日期格式非法被拒（yyyy-MM-dd 契约）", () => {
    expect(SearchAnnouncementsParamsSchema.safeParse({ from: "2026/09/01" }).success).toBe(false);
    expect(SearchAnnouncementsParamsSchema.safeParse({ to: "20260928" }).success).toBe(false);
  });
});
