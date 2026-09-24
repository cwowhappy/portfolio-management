import { z } from "zod";
import {
  AssessmentViewSchema, BacktestViewSchema, DeviationViewSchema, PlanViewSchema, QuestionnaireViewSchema,
  RebalanceViewSchema, TemplateViewSchema,
} from "./schemas";
import type {
  AssetClass, AssessmentView, BacktestView, DeviationView, PlanSource, PlanView, QuestionnaireView,
  RebalanceFrequency, RebalanceView, TemplateView, WeightView,
} from "./types";
import { get, request } from "./http";

export const ASSET_CLASSES: AssetClass[] = ["STOCK", "BOND", "GOLD", "CASH", "REITS"];
export const ASSET_CLASS_LABELS: Record<AssetClass, string> = {
  STOCK: "股票", BOND: "债券", GOLD: "黄金", CASH: "现金", REITS: "REITs",
};
export const REBALANCE_FREQUENCY_LABELS: Record<RebalanceFrequency, string> = {
  OFF: "关闭", QUARTERLY: "季度", SEMIANNUAL: "半年",
};

export const fetchTemplates = () => request<TemplateView[]>("/api/allocation/templates", "GET", undefined, z.array(TemplateViewSchema));
export const fetchPlans = () => request<PlanView[]>("/api/allocation/plans", "GET", undefined, z.array(PlanViewSchema));
export const createPlan = (cmd: { name: string; source: PlanSource; weights: WeightView[]; rebalanceFrequency?: RebalanceFrequency }) =>
  request<PlanView>("/api/allocation/plans", "POST", cmd, PlanViewSchema);
export const updatePlan = (planId: number, cmd: { name: string; weights: WeightView[]; rebalanceFrequency?: RebalanceFrequency }) =>
  request<PlanView>(`/api/allocation/plans/${planId}`, "PUT", cmd, PlanViewSchema);
export const activatePlan = (planId: number) =>
  request<PlanView>(`/api/allocation/plans/${planId}/activate`, "POST", undefined, PlanViewSchema);
export const deletePlan = (planId: number) => request<void>(`/api/allocation/plans/${planId}`, "DELETE");
export const fetchDeviation = () => request<DeviationView>("/api/allocation/deviation", "GET", undefined, DeviationViewSchema);
export const fetchRebalance = () =>
  request<RebalanceView>("/api/allocation/rebalance", "GET", undefined, RebalanceViewSchema);
export const ackRebalance = () => request<void>("/api/allocation/rebalance/ack", "POST");

// —— 配置回测（MS-13 M07-F06）——

export interface BacktestParams {
  /** 权重来源，后端优先级 planId > template > 生效方案（均未给且无生效方案 → 400 NO_ACTIVE_PLAN） */
  planId?: number;
  template?: string;
  /** 3Y | 5Y | MAX（后端默认 5Y） */
  window: string;
  /** never | quarterly | annual（后端默认 never） */
  rebalance: string;
}

/** 回测查询串构造（照 screeningApi.buildQuery 先例：跳过 undefined/null/空串）。 */
function buildBacktestQuery(params: BacktestParams): URLSearchParams {
  const qs = new URLSearchParams();
  const entries: [string, unknown][] = [
    ["planId", params.planId],
    ["template", params.template],
    ["window", params.window],
    ["rebalance", params.rebalance],
  ];
  for (const [k, v] of entries) {
    if (v !== undefined && v !== null && v !== "") qs.set(k, String(v));
  }
  return qs;
}

export const fetchBacktest = (params: BacktestParams) =>
  request<BacktestView>(
    `/api/allocation/backtest?${buildBacktestQuery(params).toString()}`,
    "GET", undefined, BacktestViewSchema,
  );

export const fetchQuestionnaire = () =>
  get<QuestionnaireView>("/api/allocation/assessment/questionnaire", QuestionnaireViewSchema);
export const fetchAssessment = () =>
  request<AssessmentView | undefined>("/api/allocation/assessment", "GET", undefined, AssessmentViewSchema);
export const submitAssessment = (answers: { questionId: string; optionId: string }[]) =>
  request<AssessmentView>("/api/allocation/assessment", "POST", { answers }, AssessmentViewSchema);
