import { z } from "zod";
import { request } from "./http";
import { fetchEntries } from "./journalApi";
import { fetchWikiEntries } from "./wikiApi";
import {
  FalsifierViewSchema,
  ProjectDetailViewSchema,
  ProjectViewSchema,
  StrategyViewSchema,
} from "./researchSchemas";
import type {
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
