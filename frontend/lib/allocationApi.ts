import { z } from "zod";
import {
  DeviationViewSchema, PlanViewSchema, TemplateViewSchema,
} from "./schemas";
import type {
  AssetClass, DeviationView, PlanSource, PlanView, TemplateView, WeightView,
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

export const ASSET_CLASSES: AssetClass[] = ["STOCK", "BOND", "GOLD", "CASH", "REITS"];
export const ASSET_CLASS_LABELS: Record<AssetClass, string> = {
  STOCK: "股票", BOND: "债券", GOLD: "黄金", CASH: "现金", REITS: "REITs",
};

export const fetchTemplates = () => request<TemplateView[]>("/api/allocation/templates", "GET", undefined, z.array(TemplateViewSchema));
export const fetchPlans = () => request<PlanView[]>("/api/allocation/plans", "GET", undefined, z.array(PlanViewSchema));
export const createPlan = (cmd: { name: string; source: PlanSource; weights: WeightView[] }) =>
  request<PlanView>("/api/allocation/plans", "POST", cmd, PlanViewSchema);
export const updatePlan = (planId: number, cmd: { name: string; weights: WeightView[] }) =>
  request<PlanView>(`/api/allocation/plans/${planId}`, "PUT", cmd, PlanViewSchema);
export const activatePlan = (planId: number) =>
  request<PlanView>(`/api/allocation/plans/${planId}/activate`, "POST", undefined, PlanViewSchema);
export const deletePlan = (planId: number) => request<void>(`/api/allocation/plans/${planId}`, "DELETE");
export const fetchDeviation = () => request<DeviationView>("/api/allocation/deviation", "GET", undefined, DeviationViewSchema);
