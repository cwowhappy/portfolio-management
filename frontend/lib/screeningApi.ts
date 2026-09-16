// 价值筛选 REST 客户端（经 /api/screening 反代），响应用 zod 在边界校验。
// 导出链接与筛选请求共用同一查询串构造，保证导出口径与页面一致。

import { z } from "zod";
import { ScreeningStockSchema } from "./schemas";
import type { ScreeningStock, ScreeningParams } from "./types";
import { get } from "./http";

/** 筛选/导出共用的查询串构造（含 亿元→元 换算）。 */
function buildQuery(params: ScreeningParams): URLSearchParams {
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
    ["indexCode", params.indexCode],
    ["sortBy", params.sortBy],
    ["sortDirection", params.sortDirection],
    ["limit", params.limit],
  ];
  for (const [k, v] of entries) {
    if (v !== undefined && v !== null && v !== "") qs.set(k, String(v));
  }
  return qs;
}

export function fetchScreenedStocks(params: ScreeningParams): Promise<ScreeningStock[]> {
  return get(`/api/screening/stocks?${buildQuery(params).toString()}`, z.array(ScreeningStockSchema));
}

/** 导出 URL 与筛选请求同参——供 <a href download> 直连触发浏览器原生下载（不经 zod JSON 链路）。 */
export function buildExportHref(params: ScreeningParams): string {
  return `/api/screening/stocks/export?${buildQuery(params).toString()}`;
}
