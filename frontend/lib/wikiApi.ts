import { z } from "zod";
import { PrincipleRuleViewSchema, WikiEntryViewSchema } from "./schemas";
import type { PrincipleMetric, PrincipleRuleView, WikiEntryType, WikiEntryView } from "./types";
import { request } from "./http";

export interface WikiEntryInput {
  type: WikiEntryType;
  title: string;
  content: string;
  category?: string | null;
  industryCode?: string | null;
}

export interface PrincipleRuleInput {
  metric: PrincipleMetric;
  threshold: number;
  enabled: boolean;
  description?: string | null;
}

export const WIKI_ENTRY_TYPE_LABELS: Record<WikiEntryType, string> = {
  BOOK_NOTE: "读书笔记", CONCEPT: "概念速查", RESEARCH_NOTE: "研究笔记",
};

export const PRINCIPLE_METRIC_LABELS: Record<PrincipleMetric, string> = {
  SINGLE_POSITION_RATIO: "单票仓位上限",
  INDUSTRY_POSITION_RATIO: "单行业仓位上限",
  STOCK_PE_MAX: "个股PE上限",
  STOCK_PB_MAX: "个股PB上限",
};

/** 比例类指标（阈值 UI 显示 %、存储 0~1）；与后端 PrincipleMetric.isRatio() 同口径 */
export const RATIO_METRICS: ReadonlySet<PrincipleMetric> = new Set([
  "SINGLE_POSITION_RATIO", "INDUSTRY_POSITION_RATIO",
]);

export const fetchWikiEntries = (type?: WikiEntryType) =>
  request<WikiEntryView[]>(`/api/wiki/entries${type ? `?type=${type}` : ""}`, "GET", undefined, z.array(WikiEntryViewSchema));
export const createWikiEntry = (cmd: WikiEntryInput) =>
  request<WikiEntryView>("/api/wiki/entries", "POST", cmd, WikiEntryViewSchema);
export const updateWikiEntry = (id: number, cmd: WikiEntryInput) =>
  request<WikiEntryView>(`/api/wiki/entries/${id}`, "PUT", cmd, WikiEntryViewSchema);
export const deleteWikiEntry = (id: number) =>
  request<void>(`/api/wiki/entries/${id}`, "DELETE");

export const fetchRules = () =>
  request<PrincipleRuleView[]>("/api/wiki/rules", "GET", undefined, z.array(PrincipleRuleViewSchema));
export const createRule = (cmd: PrincipleRuleInput) =>
  request<PrincipleRuleView>("/api/wiki/rules", "POST", cmd, PrincipleRuleViewSchema);
export const updateRule = (id: number, cmd: PrincipleRuleInput) =>
  request<PrincipleRuleView>(`/api/wiki/rules/${id}`, "PUT", cmd, PrincipleRuleViewSchema);
export const deleteRule = (id: number) =>
  request<void>(`/api/wiki/rules/${id}`, "DELETE");
