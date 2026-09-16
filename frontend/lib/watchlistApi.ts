// 自选观察 REST 客户端（经 /api/watchlist 反代，需登录）；搜索候选走公开 /api/screening。

import { z } from "zod";
import { StockSearchHitSchema, WatchlistItemViewSchema } from "./schemas";
import type { StockSearchHit, WatchlistItemView } from "./types";
import { get, request } from "./http";

export const fetchWatchlist = () =>
  get<WatchlistItemView[]>("/api/watchlist", z.array(WatchlistItemViewSchema));
export const addToWatchlist = (stockCode: string) =>
  request<void>("/api/watchlist", "POST", { stockCode });
export const removeFromWatchlist = (stockCode: string) =>
  request<void>(`/api/watchlist/${stockCode}`, "DELETE");
export const searchStocks = (q: string) =>
  get<StockSearchHit[]>(`/api/screening/stocks/search?q=${encodeURIComponent(q)}&limit=10`,
    z.array(StockSearchHitSchema));
