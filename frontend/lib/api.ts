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

async function get<T>(path: string, schema: z.ZodType<T>): Promise<T> {
  const res = await fetch(path, { cache: "no-store" });
  if (!res.ok) {
    let message = "请求失败";
    try {
      const body = await res.json();
      if (body?.message) message = body.message;
    } catch {
      // ignore
    }
    throw new Error(message);
  }
  const data: unknown = await res.json();
  try {
    return schema.parse(data);
  } catch (e) {
    console.error("[api] 响应 schema 校验失败", path, e);
    throw new Error("数据格式异常");
  }
}

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
export const fetchHealth = () => get<Health>("/api/agent/health", HealthSchema);
