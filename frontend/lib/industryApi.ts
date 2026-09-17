// 行业研究中心 REST 客户端（经 /api/industry 反代），zod 边界校验。
import { z } from "zod";
import { IndustryBoardItemSchema, IndustryStockSchema } from "./schemas";
import type { IndustryBoardItem, IndustryStock } from "./types";
import { get } from "./http";

export function fetchIndustryBoard(): Promise<IndustryBoardItem[]> {
  return get(`/api/industry/board`, z.array(IndustryBoardItemSchema));
}

export function fetchIndustryStocks(
  industryCode: string,
  params: { sortBy?: "total_mv" | "revenue" | "roe"; sortDirection?: "ASC" | "DESC"; limit?: number } = {},
): Promise<IndustryStock[]> {
  const qs = new URLSearchParams();
  if (params.sortBy) qs.set("sortBy", params.sortBy);
  if (params.sortDirection) qs.set("sortDirection", params.sortDirection);
  if (params.limit != null) qs.set("limit", String(params.limit));
  return get(`/api/industry/${encodeURIComponent(industryCode)}/stocks?${qs.toString()}`, z.array(IndustryStockSchema));
}
