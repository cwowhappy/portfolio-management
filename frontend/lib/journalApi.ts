import { z } from "zod";
import { JournalEntryViewSchema, TimelineEventViewSchema } from "./schemas";
import type { JournalEntryType, JournalEntryView, PeriodType, TimelineEventView } from "./types";

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

export interface EntryInput {
  type: JournalEntryType;
  stockCode?: string | null;
  stockName?: string | null;
  tradeId?: number | null;
  title: string;
  content: string;
  targetPrice?: number | null;
  stopLoss?: number | null;
  periodType?: PeriodType | null;
  periodStart?: string | null;
  periodEnd?: string | null;
  eventDate: string;
}

export const JOURNAL_ENTRY_TYPE_LABELS: Record<JournalEntryType, string> = {
  BUY_MEMO: "买入备忘", SELL_MEMO: "卖出备忘", RESEARCH_NOTE: "研究笔记", REVIEW: "定期复盘",
};
export const PERIOD_TYPE_LABELS: Record<PeriodType, string> = { QUARTERLY: "季度", ANNUAL: "年度" };
export const TIMELINE_EVENT_TYPE_LABELS: Record<string, string> = {
  BUY: "买入", SELL: "卖出", DIVIDEND: "分红",
  BUY_MEMO: "买入备忘", SELL_MEMO: "卖出备忘", RESEARCH_NOTE: "研究笔记", REVIEW: "复盘",
};

export const fetchEntries = (type?: JournalEntryType) =>
  request<JournalEntryView[]>(`/api/journal/entries${type ? `?type=${type}` : ""}`, "GET", undefined, z.array(JournalEntryViewSchema));
export const fetchTimeline = (from?: string, to?: string) => {
  const qs = [from && `from=${from}`, to && `to=${to}`].filter(Boolean).join("&");
  return request<TimelineEventView[]>(`/api/journal/timeline${qs ? `?${qs}` : ""}`, "GET", undefined, z.array(TimelineEventViewSchema));
};
export const createEntry = (cmd: EntryInput) =>
  request<JournalEntryView>("/api/journal/entries", "POST", cmd, JournalEntryViewSchema);
export const updateEntry = (id: number, cmd: EntryInput) =>
  request<JournalEntryView>(`/api/journal/entries/${id}`, "PUT", cmd, JournalEntryViewSchema);
export const deleteEntry = (id: number) => request<void>(`/api/journal/entries/${id}`, "DELETE");
