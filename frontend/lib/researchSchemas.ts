import { z } from "zod";

// 研究项目域 zod 镜像（invest-sop P2），逐字段对齐后端 ResearchViews 五 record 与
// StageCompletionService.ManualState。REST JSON 不剥 null（AG-UI NON_NULL 剥离仅作用于
// 事件流），可空字段显式 .nullable()，主键/枚举等必填字段一律不接受 null——
// wire 契约破坏在 api 边界报「数据格式异常」而非深渲染崩溃。

export const ResearchStageSchema = z.enum(["NEW_ANALYSIS", "STRATEGY", "POSITION", "REVIEW"]);
export const ProjectStatusSchema = z.enum(["ACTIVE", "ARCHIVED"]);
/** 三态（D22）：未开始 / 进行中 / 完成——不做百分比。 */
export const StageStatusSchema = z.enum(["NOT_STARTED", "IN_PROGRESS", "COMPLETED"]);
/** 完成方式依据（D22 角标）：PENDING=未完成（无角标），AUTO=产物齐套，MANUAL=手动兜底。 */
export const CompletionBasisSchema = z.enum(["PENDING", "AUTO", "MANUAL"]);
export const StrategyStateSchema = z.enum(["DRAFT", "FINALIZED"]);
export const FalsifierKindSchema = z.enum(["PREDICATE", "EVENT"]);
export const FalsifierPredicateSchema = z.enum(["PRICE_BELOW", "PRICE_ABOVE", "PE_ABOVE", "PB_ABOVE"]);
/** PATCH manualMarks 手动覆盖值（StageCompletionService.ManualState；NULL=清除覆盖不入参）。 */
export const ManualStateSchema = z.enum(["COMPLETED", "REOPENED"]);
// P3 契约枚举（与后端 research 域枚举逐字一致）
/** 检查类型（CheckType）：BUY/ADD 从建仓计划批次发起，SELL/REDUCE 从卖出意图发起（D18）。 */
export const CheckTypeSchema = z.enum(["BUY", "ADD", "REDUCE", "SELL"]);
/** 检查单结论（CheckResult）：CONFIRMED 逐项确认 / OVERRIDDEN 越过命中项（必填理由）。 */
export const CheckResultSchema = z.enum(["CONFIRMED", "OVERRIDDEN"]);
/** 检查项三态（CheckOutcome，D5 软提醒）：UNSET 中性——未设定规则或证伪条件待核对。 */
export const CheckOutcomeSchema = z.enum(["PASS", "HIT", "UNSET"]);

export const StageCompletionSchema = z.object({
  stage: ResearchStageSchema,
  status: StageStatusSchema,
  basis: CompletionBasisSchema,
});

export const ProjectViewSchema = z.object({
  id: z.number(),
  stockCode: z.string(),
  stockName: z.string(),
  industryCode: z.string().nullable(),
  title: z.string(),
  currentStage: ResearchStageSchema,
  status: ProjectStatusSchema,
  /** 项目级情报提醒开关（M16-F11 回收，默认 true——决策 #26）。 */
  intelligenceAlertEnabled: z.boolean(),
  createdAt: z.string(),
  updatedAt: z.string(),
});

export const StrategyViewSchema = z.object({
  id: z.number(),
  state: StrategyStateSchema,
  thesis: z.string().nullable(),
  valuationLow: z.number().nullable(),
  valuationHigh: z.number().nullable(),
  positionPlan: z.string().nullable(),
  buyConditions: z.string().nullable(),
  riskNotes: z.string().nullable(),
  finalizedAt: z.string().nullable(),
  updatedAt: z.string(),
});

export const FalsifierViewSchema = z.object({
  id: z.number(),
  kind: FalsifierKindSchema,
  predicate: FalsifierPredicateSchema.nullable(),
  threshold: z.number().nullable(),
  eventChecked: z.boolean(),
  note: z.string().nullable(),
  enabled: z.boolean(),
});

/** 详情读模型：完成度为后端唯一计算点产出（S6/NFR-1，前端不重复实现）；strategy=null 表示尚未建草稿。 */
export const ProjectDetailViewSchema = z.object({
  project: ProjectViewSchema,
  completions: z.record(ResearchStageSchema, StageCompletionSchema),
  strategy: StrategyViewSchema.nullable(),
  falsifiers: z.array(FalsifierViewSchema),
});

// —— P3：建仓计划 / 纪律检查 / 证伪命中（逐字段对齐后端 ResearchViews 三 record + CheckItemResult）——

/** 单条检查项（preview 产物与 submit 快照同构回传）：规则项含 threshold/currentValue，F01/证伪核对项两值为 null。 */
export const CheckItemResultSchema = z.object({
  metric: z.string(),
  threshold: z.number().nullable(),
  currentValue: z.number().nullable(),
  outcome: CheckOutcomeSchema,
});

/** 建仓批次视图（research_entry_batch 行）：amount 可空（金额允许暂缺）。 */
export const EntryBatchViewSchema = z.object({
  seq: z.number(),
  priceLow: z.number(),
  priceHigh: z.number(),
  quantity: z.number(),
  amount: z.number().nullable(),
  ratio: z.number(),
});

/** 建仓计划视图：kellyRatio 为后端读时算得（D23 只做算术），参数缺 → null。 */
export const EntryPlanViewSchema = z.object({
  id: z.number(),
  winRate: z.number().nullable(),
  payoffRatio: z.number().nullable(),
  kellyRatio: z.number().nullable(),
  batches: z.array(EntryBatchViewSchema),
  createdAt: z.string(),
  updatedAt: z.string(),
});

/** 检查留痕视图（append-only，items 为提交时快照）。 */
export const CheckRecordViewSchema = z.object({
  id: z.number(),
  checkType: CheckTypeSchema,
  items: z.array(CheckItemResultSchema),
  result: CheckResultSchema,
  overrideReason: z.string().nullable(),
  createdAt: z.string(),
});

// —— P4 契约（与后端 research 域枚举/视图逐字一致）——
/** 复盘档位（ReviewTier，D7 三档）：月主模板默认 + 季深度归因 + 周简版兜底。 */
export const ReviewTierSchema = z.enum(["MONTHLY", "QUARTERLY", "WEEKLY"]);
/** 复盘回流状态（RefluxState，F16）：PENDING 创建 / CONFIRMED 预留两步 / REFLOWN 已回流记 wiki_entry_id。 */
export const RefluxStateSchema = z.enum(["PENDING", "CONFIRMED", "REFLOWN"]);
/** 证伪评审结论（ReviewConclusion，F15 四值）：REVISE 仅给提示位、不自动改策略（Review Focus 3）。 */
export const ReviewConclusionSchema = z.enum(["HOLD", "REDUCE", "EXIT", "REVISE"]);

/**
 * 复盘快照（创建时定格的 Composer JSON）：无数据字段为字符串「无数据」而非 null/0
 * （Review Focus 1，前端原样展示不兜底）；navSeries/periodReturn/trades 均可能为「无数据」。
 */
export const ReviewSnapshotSchema = z
  .object({
    periodStart: z.string(),
    periodEnd: z.string(),
    asOf: z.string(),
    priceBasis: z.string(),
    navBasis: z.string(),
    navSeries: z.union([z.string(), z.array(z.object({ date: z.string(), value: z.number() }))]),
    periodReturn: z.union([z.string(), z.number()]),
    trades: z.union([
      z.string(),
      z.array(
        z.object({
          id: z.number().nullable(),
          date: z.string(),
          price: z.number(),
          side: z.string(),
          afterMaxClose: z.union([z.string(), z.number()]),
          afterMinClose: z.union([z.string(), z.number()]),
        }),
      ),
    ]),
    tradeIds: z.array(z.number()),
    attributionWindow: z.array(z.object({ start: z.string(), end: z.string() })),
  })
  .passthrough();

/**
 * 复盘视图（F13/F14/F16）：snapshot/answers/overrides 为后端 @JsonRawValue 内联对象
 * （不是转义字符串；jsonb 键序不保证）；answers/overrides null=未作答；
 * tradeIds 为归因圈选软引用（自动圈选后可手动修正）。
 */
export const ReviewViewSchema = z.object({
  id: z.number(),
  projectId: z.number(),
  tier: ReviewTierSchema,
  periodStart: z.string(),
  periodEnd: z.string(),
  snapshot: ReviewSnapshotSchema,
  answers: z.record(z.string(), z.unknown()).nullable(),
  narrative: z.string().nullable(),
  overrides: z.record(z.string(), z.unknown()).nullable(),
  tradeIds: z.array(z.number()),
  refluxState: RefluxStateSchema,
  wikiEntryId: z.number().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});

/** 证伪评审留痕视图（append-only）：suggestStrategyRevise 仅 REVISE=true（提示位，不自动改策略）。 */
export const FalsifierReviewViewSchema = z.object({
  id: z.number(),
  projectId: z.number(),
  hitId: z.number().nullable(),
  conclusion: ReviewConclusionSchema,
  reason: z.string(),
  suggestStrategyRevise: z.boolean(),
  createdAt: z.string(),
});

/** 模板改进建议视图（F16 只收集不生效；reviewId 为来源复盘可空软引用）。 */
export const FeedbackViewSchema = z.object({
  id: z.number(),
  projectId: z.number(),
  reviewId: z.number().nullable(),
  stage: ResearchStageSchema,
  content: z.string(),
  createdAt: z.string(),
});

/**
 * 证伪命中合并视图行（D21）：realtime=true 实时求值（不落库，hitAt=null）；
 * realtime=false 历史 hit 留痕行（id/hitAt 为落库标识）。EVENT 条目 hit 恒 false——
 * 展示按 basis（已确认事件/待人工勾选）或 eventChecked，不得单读 hit（Ruling-18）；
 * 历史行 hit=false 自述误导——展示按 realtime 标志给「历史命中」标签。历史行在条件已被
 * 整替删除时现态字段（kind/predicate/threshold/note）为 null，仅保 basis 与 falsifierId。
 */
export const FalsifierHitViewSchema = z.object({
  id: z.number().nullable(),
  falsifierId: z.number(),
  kind: FalsifierKindSchema.nullable(),
  predicate: FalsifierPredicateSchema.nullable(),
  threshold: z.number().nullable(),
  note: z.string().nullable(),
  eventChecked: z.boolean(),
  hit: z.boolean(),
  pending: z.boolean(),
  skipped: z.boolean(),
  basis: z.string().nullable(),
  realtime: z.boolean(),
  hitAt: z.string().nullable(),
});

export type ResearchStage = z.infer<typeof ResearchStageSchema>;
export type ProjectStatus = z.infer<typeof ProjectStatusSchema>;
export type StageStatus = z.infer<typeof StageStatusSchema>;
export type CompletionBasis = z.infer<typeof CompletionBasisSchema>;
export type StrategyState = z.infer<typeof StrategyStateSchema>;
export type FalsifierKind = z.infer<typeof FalsifierKindSchema>;
export type FalsifierPredicate = z.infer<typeof FalsifierPredicateSchema>;
export type ManualState = z.infer<typeof ManualStateSchema>;
export type StageCompletion = z.infer<typeof StageCompletionSchema>;
export type ProjectView = z.infer<typeof ProjectViewSchema>;
export type StrategyView = z.infer<typeof StrategyViewSchema>;
export type FalsifierView = z.infer<typeof FalsifierViewSchema>;
export type ProjectDetailView = z.infer<typeof ProjectDetailViewSchema>;
export type CheckType = z.infer<typeof CheckTypeSchema>;
export type CheckResult = z.infer<typeof CheckResultSchema>;
export type CheckOutcome = z.infer<typeof CheckOutcomeSchema>;
export type CheckItemResult = z.infer<typeof CheckItemResultSchema>;
export type EntryBatchView = z.infer<typeof EntryBatchViewSchema>;
export type EntryPlanView = z.infer<typeof EntryPlanViewSchema>;
export type CheckRecordView = z.infer<typeof CheckRecordViewSchema>;
export type FalsifierHitView = z.infer<typeof FalsifierHitViewSchema>;
export type ReviewTier = z.infer<typeof ReviewTierSchema>;
export type RefluxState = z.infer<typeof RefluxStateSchema>;
export type ReviewConclusion = z.infer<typeof ReviewConclusionSchema>;
export type ReviewView = z.infer<typeof ReviewViewSchema>;
export type FalsifierReviewView = z.infer<typeof FalsifierReviewViewSchema>;
export type FeedbackView = z.infer<typeof FeedbackViewSchema>;

/** 四阶段声明序（与后端 ResearchStage 枚举一致，进度条/过滤/选择器共用）。 */
export const RESEARCH_STAGES: readonly ResearchStage[] = ["NEW_ANALYSIS", "STRATEGY", "POSITION", "REVIEW"];

// 中文标签与后端各枚举 label() 对齐
export const RESEARCH_STAGE_LABELS: Record<ResearchStage, string> = {
  NEW_ANALYSIS: "新分析",
  STRATEGY: "制定投资策略",
  POSITION: "建仓与持仓",
  REVIEW: "复盘",
};
export const PROJECT_STATUS_LABELS: Record<ProjectStatus, string> = {
  ACTIVE: "研究中",
  ARCHIVED: "已归档",
};
export const STAGE_STATUS_LABELS: Record<StageStatus, string> = {
  NOT_STARTED: "未开始",
  IN_PROGRESS: "进行中",
  COMPLETED: "完成",
};
export const COMPLETION_BASIS_LABELS: Record<CompletionBasis, string> = {
  PENDING: "待定",
  AUTO: "自动",
  MANUAL: "手动",
};
export const STRATEGY_STATE_LABELS: Record<StrategyState, string> = {
  DRAFT: "草稿",
  FINALIZED: "已定稿",
};
export const FALSIFIER_KIND_LABELS: Record<FalsifierKind, string> = {
  PREDICATE: "谓词",
  EVENT: "事件",
};
export const FALSIFIER_PREDICATE_LABELS: Record<FalsifierPredicate, string> = {
  PRICE_BELOW: "价格跌破",
  PRICE_ABOVE: "价格升破",
  PE_ABOVE: "PE 高于",
  PB_ABOVE: "PB 高于",
};
export const MANUAL_STATE_LABELS: Record<ManualState, string> = {
  COMPLETED: "手动完成",
  REOPENED: "已重开",
};
// P3 中文标签（与后端 CheckType/CheckResult/CheckOutcome label() 对齐）
export const CHECK_TYPE_LABELS: Record<CheckType, string> = {
  BUY: "买入",
  ADD: "加仓",
  REDUCE: "减仓",
  SELL: "卖出",
};
export const CHECK_RESULT_LABELS: Record<CheckResult, string> = {
  CONFIRMED: "确认",
  OVERRIDDEN: "越过",
};
export const CHECK_OUTCOME_LABELS: Record<CheckOutcome, string> = {
  PASS: "通过",
  HIT: "命中",
  UNSET: "未设定",
};
/** 规则类检查项 metric 枚举名 → 展示名（与后端 DisciplineCheckService 四常量逐字一致）。 */
export const CHECK_METRIC_LABELS: Record<string, string> = {
  SINGLE_POSITION_RATIO: "计划后单票占比",
  INDUSTRY_POSITION_RATIO: "计划后行业占比",
  STOCK_PE_MAX: "当前 PE",
  STOCK_PB_MAX: "当前 PB",
};
/**
 * F01 必查项（与后端 DisciplineCheckService.F01_* 常量逐字对齐）：preview 勾选键与
 * 确认卡勾选框共用——勾选=true 记 PASS，未勾选记 HIT（布尔语境，后端转换）。
 */
export const F01_MUST_ITEMS: readonly string[] = ["能力圈", "安全边际", "估值核对", "买入条件"];

// —— P4 中文标签（与后端 ReviewTier/RefluxState/ReviewConclusion label() 对齐）——
export const REVIEW_TIER_LABELS: Record<ReviewTier, string> = {
  MONTHLY: "月度复盘",
  QUARTERLY: "季度复盘",
  WEEKLY: "周度复盘",
};
export const REFLUX_STATE_LABELS: Record<RefluxState, string> = {
  PENDING: "待回流",
  CONFIRMED: "已确认",
  REFLOWN: "已回流",
};
export const REVIEW_CONCLUSION_LABELS: Record<ReviewConclusion, string> = {
  HOLD: "维持",
  REDUCE: "减仓",
  EXIT: "退出",
  REVISE: "修订策略",
};

// —— P4 复盘表单字段常量表（常量表驱动：MS-24 字段集收敛后只改本表、不动组件）——

/**
 * 复盘表单字段定义：内容源自 F01 定稿复盘节（skills/review SKILL.md 清单 4.1-4.12）——
 * 月主 4.1-4.5 / 季深追加 4.6-4.9 / 周简 4.10-4.12。id 同时是 answers/overrides JSONB 键；
 * 带 auto 的字段双列展示（快照自动值灰显只读 + 覆盖值可编辑写 overrides，F14），
 * 其余字段单列可编辑（写 answers）；overrideHint 的字段旁显「本期越过 N 次」（GET checks 统计）。
 */
export type ReviewFieldDef =
  | { id: string; label: string; type: "text" | "textarea"; auto?: "periodReturn"; overrideHint?: true }
  | { id: string; label: string; type: "select"; options: readonly string[]; auto?: "periodReturn"; overrideHint?: true };

const MONTHLY_FIELDS: readonly ReviewFieldDef[] = [
  { id: "4.1", label: "决策质量：检查单执行与论点新证据", type: "textarea" },
  { id: "4.2", label: "结果质量：区间收益（自动带入）", type: "text", auto: "periodReturn" },
  { id: "4.3", label: "纪律遵守度：越过与违例复盘", type: "textarea", overrideHint: true },
  {
    id: "4.4",
    label: "归因：决策/结果四象限",
    type: "select",
    options: ["决策对/结果对", "决策对/结果错（运气坏）", "决策错/结果对（运气好）", "决策错/结果错"],
  },
  { id: "4.5", label: "经验条目（≤3 条可复用）", type: "textarea" },
];

const QUARTERLY_EXTRA_FIELDS: readonly ReviewFieldDef[] = [
  { id: "4.6", label: "论点全量重检（论据链逐条重验）", type: "textarea" },
  { id: "4.7", label: "估值区间重算留痕", type: "textarea" },
  { id: "4.8", label: "知道的更多了什么（对比最初买入）", type: "textarea" },
  { id: "4.9", label: "持有逻辑是否需要修订", type: "select", options: ["需要修订", "维持不变"] },
];

const WEEKLY_FIELDS: readonly ReviewFieldDef[] = [
  { id: "4.10", label: "本周有无触发证伪条件", type: "select", options: ["是", "否"] },
  { id: "4.11", label: "计划外操作（有则一句话理由）", type: "text" },
  { id: "4.12", label: "下周关注点", type: "text" },
];

/** 三档字段清单（季档 = 月档全量 + 追加；周档独立简版）。 */
export const REVIEW_FIELDS: Record<ReviewTier, readonly ReviewFieldDef[]> = {
  MONTHLY: MONTHLY_FIELDS,
  QUARTERLY: [...MONTHLY_FIELDS, ...QUARTERLY_EXTRA_FIELDS],
  WEEKLY: WEEKLY_FIELDS,
};
