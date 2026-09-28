import { z } from "zod";
import { request } from "./http";
import { fetchEntries } from "./journalApi";
import { fetchWikiEntries } from "./wikiApi";
import {
  CheckItemResultSchema,
  CheckRecordViewSchema,
  EntryPlanViewSchema,
  FalsifierHitViewSchema,
  FalsifierViewSchema,
  ProjectDetailViewSchema,
  ProjectViewSchema,
  StrategyViewSchema,
} from "./researchSchemas";
import type {
  CheckItemResult,
  CheckRecordView,
  CheckResult,
  CheckType,
  EntryPlanView,
  FalsifierHitView,
  FalsifierKind,
  FalsifierPredicate,
  FalsifierView,
  ManualState,
  ProjectDetailView,
  ProjectStatus,
  ProjectView,
  ResearchStage,
  StrategyView,
} from "./researchSchemas";

// 研究项目域 api 层（invest-sop P2）：端点/请求体逐字对齐后端 ResearchController。
// PATCH 是本域特有方法（改标题/切阶段/手动标记同端整组生效）。

/** 立项命令（F05）：预填标的由筛选器/行业中心「发起研究」带入；withTemplate 带入新分析 SOP 模板。 */
export interface CreateProjectInput {
  stockCode: string;
  stockName: string;
  industryCode?: string;
  title: string;
  withTemplate?: boolean;
}

export const createProject = (cmd: CreateProjectInput) =>
  request<ProjectView>("/api/research/projects", "POST", cmd, ProjectViewSchema);

export interface ListProjectsQuery {
  stage?: ResearchStage;
  status?: ProjectStatus;
  q?: string;
}

/** 列表（F07）：后端默认滤 ARCHIVED，显式传 status 可查归档；q 匹配标题/标的（忽略大小写）。 */
export const listProjects = ({ stage, status, q }: ListProjectsQuery = {}) => {
  const qs = [
    stage && `stage=${stage}`,
    status && `status=${status}`,
    q && `q=${encodeURIComponent(q)}`,
  ]
    .filter(Boolean)
    .join("&");
  return request<ProjectView[]>(
    `/api/research/projects${qs ? `?${qs}` : ""}`,
    "GET",
    undefined,
    z.array(ProjectViewSchema),
  );
};

export const getProject = (id: number) =>
  request<ProjectDetailView>(`/api/research/projects/${id}`, "GET", undefined, ProjectDetailViewSchema);

export interface ManualMarkInput {
  stage: ResearchStage;
  state: ManualState;
}

export interface PatchProjectInput {
  title?: string;
  currentStage?: ResearchStage;
  manualMarks?: ManualMarkInput[];
}

/** PATCH：三组字段均可选，仅提交的字段生效（单事务整组生效）。 */
export const patchProject = (id: number, body: PatchProjectInput) =>
  request<ProjectDetailView>(`/api/research/projects/${id}`, "PATCH", body, ProjectDetailViewSchema);

export const archiveProject = (id: number) =>
  request<ProjectView>(`/api/research/projects/${id}/archive`, "POST", undefined, ProjectViewSchema);

export const getStrategy = (id: number) =>
  request<StrategyView>(`/api/research/projects/${id}/strategy`, "GET", undefined, StrategyViewSchema);

/** 暂存草稿（D13）：DRAFT 态六字段均可空、整组覆盖；FINALIZED 态后端 422 拒绝（须先 revise）。 */
export interface SaveStrategyDraftInput {
  thesis?: string | null;
  valuationLow?: number | null;
  valuationHigh?: number | null;
  positionPlan?: string | null;
  buyConditions?: string | null;
  riskNotes?: string | null;
}

export const saveStrategyDraft = (id: number, body: SaveStrategyDraftInput) =>
  request<StrategyView>(`/api/research/projects/${id}/strategy`, "PUT", body, StrategyViewSchema);

/** 定稿（显式动作）：估值下限须小于上限且均非空——前端预校验 + 后端 422 双保险。 */
export const finalizeStrategy = (id: number) =>
  request<StrategyView>(`/api/research/projects/${id}/strategy/finalize`, "POST", undefined, StrategyViewSchema);

/** 修订（D13 覆盖式）：FINALIZED→DRAFT；DRAFT 态宽容 no-op。 */
export const reviseStrategy = (id: number) =>
  request<StrategyView>(`/api/research/projects/${id}/strategy/revise`, "POST", undefined, StrategyViewSchema);

export const getFalsifiers = (id: number) =>
  request<FalsifierView[]>(`/api/research/projects/${id}/falsifiers`, "GET", undefined, z.array(FalsifierViewSchema));

/** PUT 证伪条件整替项（对齐后端 SaveFalsifierItem：kind 决定构造工厂，字段校验在域内——
 * PREDICATE 必填 predicate + 非负 threshold，EVENT 必填 note）。 */
export interface SaveFalsifierItemInput {
  kind: FalsifierKind;
  predicate?: FalsifierPredicate;
  threshold?: number;
  note?: string;
}

export const saveFalsifiers = (id: number, items: SaveFalsifierItemInput[]) =>
  request<FalsifierView[]>(`/api/research/projects/${id}/falsifiers`, "PUT", items, z.array(FalsifierViewSchema));

/** F08 反查：项目关联记录（journal RESEARCH_EVENT 等软引用条目 / wiki 研究笔记）。 */
export const getLinkedNotes = (id: number) => fetchEntries(undefined, id);
export const getLinkedWiki = (id: number) => fetchWikiEntries(undefined, id);

// —— P3：建仓计划 / 纪律检查 / 证伪命中（端点逐字对齐后端 ResearchController P3-T4 段）——

/** 建仓计划查询：未保存 → 404「建仓计划不存在」（照 strategy 先例），调用方按未保存处理。 */
export const getEntryPlan = (id: number) =>
  request<EntryPlanView>(`/api/research/projects/${id}/entry-plan`, "GET", undefined, EntryPlanViewSchema);

/** PUT 建仓批次项（字段域校验在后端 EntryBatch：Σratio>1 → 422 RATIO_SUM_EXCEEDED 唯一硬拒绝）。 */
export interface SaveEntryBatchItemInput {
  seq: number;
  priceLow: number;
  priceHigh: number;
  quantity: number;
  amount: number | null;
  ratio: number;
}

/** PUT 建仓计划命令（整替：plan+batches 同事务重建；winRate/payoffRatio 可空=不估 kelly，D23）。 */
export interface SaveEntryPlanInput {
  winRate: number | null;
  payoffRatio: number | null;
  batches: SaveEntryBatchItemInput[];
}

export const saveEntryPlan = (id: number, body: SaveEntryPlanInput) =>
  request<EntryPlanView>(`/api/research/projects/${id}/entry-plan`, "PUT", body, EntryPlanViewSchema);

/** 发起检查命令：f01MustItems 键与后端 DisciplineCheckService.F01_* 常量逐字对齐（F01_MUST_ITEMS）。 */
export interface PreviewCheckInput {
  checkType: CheckType;
  f01MustItems: Record<string, boolean>;
}

/** 发起纪律检查（纯读不落库，D5 软提醒）：返回命中项列表。 */
export const previewCheck = (id: number, body: PreviewCheckInput) =>
  request<CheckItemResult[]>(
    `/api/research/projects/${id}/checks/preview`,
    "POST",
    body,
    z.array(CheckItemResultSchema),
  );

/** 提交检查命令：items 为 preview 快照原样回传（留痕定格）；OVERRIDDEN 必填 overrideReason。 */
export interface SubmitCheckInput {
  checkType: CheckType;
  result: CheckResult;
  overrideReason?: string;
  items: CheckItemResult[];
}

/** 提交检查留痕（append-only + journal 事件；OVERRIDDEN 缺理由 → 422）。 */
export const submitCheck = (id: number, body: SubmitCheckInput) =>
  request<CheckRecordView>(`/api/research/projects/${id}/checks`, "POST", body, CheckRecordViewSchema);

/** 证伪命中合并视图（D21）：实时求值 + 历史 hit 留痕同一列表（行自带 realtime 标志）。 */
export const getHits = (id: number) =>
  request<FalsifierHitView[]>(
    `/api/research/projects/${id}/falsifier/hits`,
    "GET",
    undefined,
    z.array(FalsifierHitViewSchema),
  );
