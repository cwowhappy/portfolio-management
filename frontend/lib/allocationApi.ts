import { z } from "zod";
import {
  DeviationViewSchema, PlanViewSchema, TemplateViewSchema,
} from "./schemas";
import type {
  AssetClass, DeviationView, PlanSource, PlanView, TemplateView, WeightView,
} from "./types";
import { request } from "./http";

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
