// 价值筛选 REST 客户端（经 /api/screening 反代），响应用 zod 在边界校验。

import { z } from "zod";
import { ScreeningStockSchema } from "./schemas";
import type { ScreeningStock, ScreeningParams } from "./types";
import { get } from "./http";

export function fetchScreenedStocks(params: ScreeningParams): Promise<ScreeningStock[]> {
  const qs = new URLSearchParams();
  const entries: [string, unknown][] = [
    ["peTtmMax", params.peTtmMax],
    ["pbMax", params.pbMax],
    ["dividendYieldMin", params.dividendYieldMin],
    ["roeMin", params.roeMin],
    ["roaMin", params.roaMin],
    ["grossMarginMin", params.grossMarginMin],
    ["debtToAssetsMax", params.debtToAssetsMax],
    ["currentRatioMin", params.currentRatioMin],
    ["revenueYoyMin", params.revenueYoyMin],
    ["netprofitYoyMin", params.netprofitYoyMin],
    // 总市值：前端「亿元」→ 后端「元」
    ["totalMvMin", params.totalMvMin != null ? params.totalMvMin * 1e8 : undefined],
    ["turnoverRateMin", params.turnoverRateMin],
    ["industryCode", params.industryCode],
    ["sortBy", params.sortBy],
    ["sortDirection", params.sortDirection],
    ["limit", params.limit],
  ];
  for (const [k, v] of entries) {
    if (v !== undefined && v !== null && v !== "") qs.set(k, String(v));
  }
  return get(`/api/screening/stocks?${qs.toString()}`, z.array(ScreeningStockSchema));
}
