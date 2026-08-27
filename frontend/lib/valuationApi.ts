// 估值 REST 客户端（经 /api/valuation 反代），响应用 zod 在边界校验。

import { z } from "zod";
import { ValuationOverviewSchema, IndustryValuationSchema, ValuationHistorySchema } from "./schemas";
import type { ValuationOverview, IndustryValuation, ValuationHistory } from "./types";

async function get<T>(path: string, schema: z.ZodType<T>): Promise<T> {
  const res = await fetch(path, { cache: "no-store" });
  if (!res.ok) throw new Error(`请求失败 (${res.status})`);
  const data = await res.json();
  const parsed = schema.safeParse(data);
  if (!parsed.success) throw new Error("数据格式异常");
  return parsed.data;
}

export function fetchValuationOverview(): Promise<ValuationOverview> {
  return get("/api/valuation/overview", ValuationOverviewSchema);
}

export function fetchValuationHistory(): Promise<ValuationHistory> {
  return get("/api/valuation/history", ValuationHistorySchema);
}

export function fetchValuationIndustries(sort = "pe"): Promise<IndustryValuation[]> {
  return get(`/api/valuation/industries?sort=${encodeURIComponent(sort)}`, z.array(IndustryValuationSchema));
}
