import { z } from "zod";

// 投研草稿 zod 镜像（invest-sop P1），逐字段对齐后端 ResearchDraftSpec.java 四变体 sealed interface。
// 后端每 record 标 @JsonInclude(NON_NULL)：wire 上可选字段「缺失≠null」——除判别组件
// （specVersion/stage）与 quantity（后端缺省默认 0，恒在）外全部 .optional()，一律不接受 null。
// specVersion: z.literal(1) 锁版本：未知版本拒绝，前端降级卡兜底（P2 升版本双端同步）。
export const FalsifierItemSchema = z.object({
  kind: z.string().optional(),
  predicate: z.string().optional(),
  threshold: z.number().optional(),
  note: z.string().optional(),
});

export const BatchItemSchema = z.object({
  priceLow: z.number().optional(),
  priceHigh: z.number().optional(),
  quantity: z.number(),          // 后端 longValue 缺省 0、longValueExact 保证整数，wire 恒在
  ratio: z.number().optional(),  // 预计仓位占比（Σratio ≤ 1，设计规格 §二）
});

export const AnalysisDraftSchema = z.object({
  specVersion: z.literal(1),
  stage: z.literal("NEW_ANALYSIS"),
  symbol: z.string().optional(),
  companyName: z.string().optional(),
  industry: z.string().optional(),
  checklistDone: z.array(z.string()).optional(),
  summary: z.string().optional(),
});

export const StrategyDraftSchema = z.object({
  specVersion: z.literal(1),
  stage: z.literal("STRATEGY"),
  thesis: z.string().optional(),
  valuationLow: z.number().optional(),
  valuationHigh: z.number().optional(),
  positionPlan: z.string().optional(),
  buyConditions: z.string().optional(),
  riskItems: z.array(FalsifierItemSchema).optional(),
});

export const EntryPlanDraftSchema = z.object({
  specVersion: z.literal(1),
  stage: z.literal("POSITION"),
  batches: z.array(BatchItemSchema).optional(),
  winRate: z.number().optional(),
  payoffRatio: z.number().optional(),
  note: z.string().optional(),
});

export const ReviewDraftSchema = z.object({
  specVersion: z.literal(1),
  stage: z.literal("REVIEW"),
  tier: z.string().optional(),        // MONTHLY/QUARTERLY/WEEKLY（设计规格 D7），展示用不锁枚举
  periodStart: z.string().optional(),
  periodEnd: z.string().optional(),
  narrative: z.string().optional(),
});

export const ResearchDraftSchema = z.discriminatedUnion("stage", [
  AnalysisDraftSchema,
  StrategyDraftSchema,
  EntryPlanDraftSchema,
  ReviewDraftSchema,
]);

export type FalsifierItem = z.infer<typeof FalsifierItemSchema>;
export type BatchItem = z.infer<typeof BatchItemSchema>;
export type AnalysisDraft = z.infer<typeof AnalysisDraftSchema>;
export type StrategyDraft = z.infer<typeof StrategyDraftSchema>;
export type EntryPlanDraft = z.infer<typeof EntryPlanDraftSchema>;
export type ReviewDraft = z.infer<typeof ReviewDraftSchema>;
export type ResearchDraft = z.infer<typeof ResearchDraftSchema>;

// research_draft 工具结果文本形态（InvestTools.researchDraft）：
//   摘要 + "\n```research-draft\n" + 序列化 JSON + "\n```"
const RESEARCH_DRAFT_FENCE_RE = /```research-draft\s*\n([\s\S]*?)```/;

/** 从工具结果文本提取 ```research-draft 围栏内的 JSON 文本；无围栏（如参数错误文本）返回 null。
 *  惰性匹配取首个围栏；围栏内容原样返回（含 JSON 解析失败的场景，交 DraftCard 降级兜底）。 */
export function extractResearchDraftJson(text: string): string | null {
  const m = RESEARCH_DRAFT_FENCE_RE.exec(text);
  return m == null ? null : m[1].trim();
}
