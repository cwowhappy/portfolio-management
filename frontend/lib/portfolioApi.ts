import { z } from "zod";
import {
  AssetAllocationSchema, CashTransactionViewSchema, ConcentrationSchema, GroupViewSchema,
  IndustryDistributionSchema, PortfolioOverviewSchema, PositionViewSchema, TradeViewSchema,
} from "./schemas";
import type {
  AssetAllocation, CashTransactionView, Concentration, GroupView, IndustryDistribution,
  PortfolioOverview, PositionView, TradeView,
} from "./types";

async function request<T>(path: string, method: string, body?: unknown, schema?: z.ZodType<T>): Promise<T> {
  const res = await fetch(path, {
    method,
    headers: body !== undefined ? { "Content-Type": "application/json" } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
    cache: "no-store",
  });
  if (!res.ok) {
    let message = "请求失败";
    try { const b = await res.json(); if (b?.message) message = b.message; } catch { /* ignore */ }
    throw new Error(message);
  }
  if (res.status === 204) return undefined as T;
  const data: unknown = await res.json();
  return schema ? schema.parse(data) : (data as T);
}

export const fetchOverview = () => request<PortfolioOverview>("/api/portfolio/overview", "GET", undefined, PortfolioOverviewSchema);
export const fetchPositions = (groupId?: number) =>
  request<PositionView[]>(`/api/portfolio/positions${groupId != null ? `?groupId=${groupId}` : ""}`, "GET", undefined, z.array(PositionViewSchema));
export const fetchGroups = () => request<GroupView[]>("/api/portfolio/groups", "GET", undefined, z.array(GroupViewSchema));
export const createGroup = (cmd: { name: string; type: "ACCOUNT" | "TAG" }) =>
  request<GroupView>("/api/portfolio/groups", "POST", cmd, GroupViewSchema);
export const renameGroup = (groupId: number, cmd: { name: string }) =>
  request<GroupView>(`/api/portfolio/groups/${groupId}`, "PUT", cmd, GroupViewSchema);
export const deleteGroup = (groupId: number) => request<void>(`/api/portfolio/groups/${groupId}`, "DELETE");
export const buy = (cmd: { groupId: number; stockCode: string; stockName: string; tradeDate: string; price: number; quantity: number; fee: number }) =>
  request<PositionView>("/api/portfolio/positions/buy", "POST", cmd, PositionViewSchema);
export const sell = (cmd: { positionId: number; tradeDate: string; price: number; quantity: number; fee: number }) =>
  request<PositionView>("/api/portfolio/positions/sell", "POST", cmd, PositionViewSchema);
export const addCashDividend = (cmd: { positionId: number; exDate: string; cashPerShare: number }) =>
  request<PositionView>("/api/portfolio/positions/cash-dividend", "POST", cmd, PositionViewSchema);
export const addStockDividend = (cmd: { positionId: number; exDate: string; stockRatio: number }) =>
  request<PositionView>("/api/portfolio/positions/stock-dividend", "POST", cmd, PositionViewSchema);
export const deletePosition = (positionId: number) => request<void>(`/api/portfolio/positions/${positionId}`, "DELETE");
export const fetchTrades = (positionId: number) =>
  request<TradeView[]>(`/api/portfolio/positions/${positionId}/trades`, "GET", undefined, z.array(TradeViewSchema));
export const editTrade = (positionId: number, tradeId: number, cmd: { tradeDate: string; price: number; quantity: number; fee: number }) =>
  request<PositionView>(`/api/portfolio/positions/${positionId}/trades/${tradeId}`, "PUT", cmd, PositionViewSchema);
export const addCashTransaction = (cmd: { groupId: number; type: "DEPOSIT" | "WITHDRAW"; amount: number; txDate: string; note?: string }) =>
  request<CashTransactionView>("/api/portfolio/cash-transactions", "POST", cmd, CashTransactionViewSchema);
export const fetchAllocation = () => request<AssetAllocation>("/api/portfolio/allocation", "GET", undefined, AssetAllocationSchema);
export const fetchIndustryDistribution = () => request<IndustryDistribution>("/api/portfolio/industry-distribution", "GET", undefined, IndustryDistributionSchema);
export const fetchConcentration = () => request<Concentration>("/api/portfolio/concentration", "GET", undefined, ConcentrationSchema);
