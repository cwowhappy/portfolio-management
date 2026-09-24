import { z } from "zod";
import { AnalyticsNavSchema, AnalyticsOverviewSchema, AnnualReturnRowSchema, RiskStatsViewSchema, TradeStatsViewSchema } from "./schemas";
import type { AnalyticsNav, AnalyticsOverview, AnnualReturnRow, RiskStatsView, TradeStatsView } from "./types";
import { request } from "./http";

// overview/nav/trade-stats/risk-stats 无流水时后端 204：http.request 对 204 不抛错、返回 undefined，
// 故出参类型显式带 undefined（对齐 allocationApi.fetchAssessment 先例）；annual 恒返回数组。
export const fetchOverview = () => request<AnalyticsOverview | undefined>("/api/analytics/overview", "GET", undefined, AnalyticsOverviewSchema);
export const fetchNav = () => request<AnalyticsNav | undefined>("/api/analytics/nav", "GET", undefined, AnalyticsNavSchema);
export const fetchAnnual = () => request<AnnualReturnRow[]>("/api/analytics/annual", "GET", undefined, z.array(AnnualReturnRowSchema));
export const fetchTradeStats = () => request<TradeStatsView | undefined>("/api/analytics/trade-stats", "GET", undefined, TradeStatsViewSchema);
export const fetchRiskStats = () => request<RiskStatsView | undefined>("/api/analytics/risk-stats", "GET", undefined, RiskStatsViewSchema);
