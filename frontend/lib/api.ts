// 行情 REST 客户端（经 /api/market 反代）。

import type {
  Financials,
  Health,
  KlineBar,
  MarketOverview,
  NewsItem,
  Quote,
  StockHit,
} from "./types";

async function get<T>(path: string): Promise<T> {
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
  return res.json() as Promise<T>;
}

export const fetchOverview = () => get<MarketOverview>("/api/market/overview");
export const fetchQuote = (code: string) => get<Quote>("/api/market/quote/" + code);
export const fetchKline = (code: string, period = "day", limit = 120) =>
  get<KlineBar[]>("/api/market/kline/" + code + "?period=" + period + "&limit=" + limit);
export const fetchFinancials = (code: string) => get<Financials>("/api/market/financials/" + code);
export const fetchNews = (code: string, limit = 10) =>
  get<NewsItem[]>("/api/market/news/" + code + "?limit=" + limit);
export const searchStocks = (q: string) =>
  get<StockHit[]>("/api/market/search?q=" + encodeURIComponent(q));
export const fetchHealth = () => get<Health>("/api/agent/health");
