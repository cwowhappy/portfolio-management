// 行情 REST 客户端（经 /api/market 反代），响应用 zod 在边界校验。

import { z } from "zod";
import {
  FinancialsSchema,
  HealthSchema,
  KlineBarSchema,
  MarketOverviewSchema,
  NewsItemSchema,
  QuoteSchema,
  StockHitSchema,
} from "./schemas";
import type {
  Financials,
  Health,
  KlineBar,
  MarketOverview,
  NewsItem,
  Quote,
  StockHit,
} from "./types";
import { get } from "./http";

export const fetchOverview = () => get<MarketOverview>("/api/market/overview", MarketOverviewSchema);
export const fetchQuote = (code: string) => get<Quote>("/api/market/quote/" + code, QuoteSchema);
export const fetchKline = (code: string, period = "day", limit = 120) =>
  get<KlineBar[]>("/api/market/kline/" + code + "?period=" + period + "&limit=" + limit, z.array(KlineBarSchema));
export const fetchFinancials = (code: string) =>
  get<Financials>("/api/market/financials/" + code, FinancialsSchema);
export const fetchNews = (code: string, limit = 10) =>
  get<NewsItem[]>("/api/market/news/" + code + "?limit=" + limit, z.array(NewsItemSchema));
export const searchStocks = (q: string) =>
  get<StockHit[]>("/api/market/search?q=" + encodeURIComponent(q), z.array(StockHitSchema));
// 完整健康结构（含行情探活）由 /api/agent/status 提供；/api/agent/health 是纯 liveness
export const fetchHealth = () => get<Health>("/api/agent/status", HealthSchema);
