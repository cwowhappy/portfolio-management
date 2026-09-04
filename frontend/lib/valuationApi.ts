// 估值 REST 客户端（经 /api/valuation 反代），响应用 zod 在边界校验。

import { z } from "zod";
import { ValuationOverviewSchema, IndustryValuationSchema, ValuationHistorySchema } from "./schemas";
import type { ValuationOverview, IndustryValuation, ValuationHistory } from "./types";
import { get } from "./http";

export function fetchValuationOverview(): Promise<ValuationOverview> {
  return get("/api/valuation/overview", ValuationOverviewSchema);
}

export function fetchValuationHistory(): Promise<ValuationHistory> {
  return get("/api/valuation/history", ValuationHistorySchema);
}

export function fetchValuationIndustries(sort = "pe"): Promise<IndustryValuation[]> {
  return get(`/api/valuation/industries?sort=${encodeURIComponent(sort)}`, z.array(IndustryValuationSchema));
}
