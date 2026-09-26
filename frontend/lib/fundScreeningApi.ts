// ETF 基金筛选 REST 客户端（经 /api/screening 反代），照 screeningApi 模式：
// 响应用 zod 在边界校验；导出链接与筛选请求共用同一查询串构造，保证导出口径与页面一致。
// 与个股筛选不同：三个数值字段后端口径即用户输入口径（feeRateMax=年化%、scaleMin=亿元、
// trackingErrorMax=小数如 0.05=5%），此处不做任何换算。

import { z } from "zod";
import { FundScreeningResultSchema } from "./schemas";
import type { FundScreeningResult, FundScreeningParams } from "./types";
import { get } from "./http";

/** 筛选/导出共用的查询串构造（undefined/空 省略，单位口径直传）。 */
function buildQuery(params: FundScreeningParams): URLSearchParams {
  const qs = new URLSearchParams();
  const entries: [string, unknown][] = [
    ["feeRateMax", params.feeRateMax],
    ["scaleMin", params.scaleMin],
    ["trackingErrorMax", params.trackingErrorMax],
    ["category", params.category],
    ["sortBy", params.sortBy],
    ["sortDirection", params.sortDirection],
    ["limit", params.limit],
  ];
  for (const [k, v] of entries) {
    if (v !== undefined && v !== null && v !== "") qs.set(k, String(v));
  }
  return qs;
}

export function fetchFundScreening(params: FundScreeningParams): Promise<FundScreeningResult[]> {
  return get(`/api/screening/funds?${buildQuery(params).toString()}`, z.array(FundScreeningResultSchema));
}

/** 导出 URL 与筛选请求同参——供 <a href download> 直连触发浏览器原生下载（不经 zod JSON 链路）。 */
export function buildFundExportHref(params: FundScreeningParams): string {
  return `/api/screening/funds/export?${buildQuery(params).toString()}`;
}
