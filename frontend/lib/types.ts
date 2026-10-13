// 领域类型（与后端 DTO 对齐）——规范 5.1 单真源：凡 lib/schemas.ts 有对应 schema 的响应 DTO
// 一律改为 z.infer 派生并从本文件原路径重导出（researchSchemas.ts 先例，消费方 import 路径不变）；
// 仅保留无 schema 对应的类型：纯前端模型（ChatMessage）、请求入参（ScreeningParams 等）与
// 提交命令体（SaveUnlistedCompanyInput 等），以及 schemas.ts 内联未单独导出的枚举字面量别名。

import { z } from "zod";
import {
  AllocationSliceSchema,
  AnalyticsNavSchema,
  AnalyticsOverviewSchema,
  AnnualReturnRowSchema,
  AssetAllocationSchema,
  AssetClassSchema,
  AssessmentViewSchema,
  AttributionRowSchema,
  AttributionSchema,
  BacktestViewSchema,
  BenchmarkComparisonSchema,
  CashTransactionViewSchema,
  ChainMemberViewSchema,
  ChainStageViewSchema,
  ChainViewSchema,
  ConcentrationHoldingSchema,
  ConcentrationSchema,
  CurationImportResultSchema,
  CurvePointViewSchema,
  DeleteImpactSchema,
  DeviationSliceSchema,
  DeviationViewSchema,
  FinancialIndicatorSchema,
  FinancialsSchema,
  FundingEventSchema,
  FundScreeningResultSchema,
  GroupViewSchema,
  HealthSchema,
  ImportResultSchema,
  ImportRowErrorSchema,
  IndexPointSchema,
  IndexQuoteSchema,
  IndexValuationPointSchema,
  IndexValuationSeriesSchema,
  IndustryBoardItemSchema,
  IndustryDistributionSchema,
  IndustrySliceSchema,
  IndustryStockSchema,
  IndustryValuationSchema,
  IndustryWatchItemSchema,
  JournalEntryTypeSchema,
  JournalEntryViewSchema,
  KlineBarSchema,
  MarketOverviewSchema,
  NavPointSchema,
  NewsItemSchema,
  OptionViewSchema,
  PeriodTypeSchema,
  PlanSourceSchema,
  PlanViewSchema,
  PortfolioOverviewSchema,
  PositionViewSchema,
  PrincipleMetricSchema,
  PrincipleRuleViewSchema,
  ProsperitySchema,
  QuestionnaireViewSchema,
  QuestionViewSchema,
  QuoteSchema,
  RebalanceFrequencySchema,
  RebalanceItemSchema,
  RebalanceTimeTriggerSchema,
  RebalanceViewSchema,
  RiskProfileSchema,
  RiskStatsViewSchema,
  RoundCountSchema,
  ScreeningStockSchema,
  StockHitSchema,
  StockSearchHitSchema,
  TemplateViewSchema,
  TimelineEventTypeSchema,
  TimelineEventViewSchema,
  TradeStatsViewSchema,
  TradeViewSchema,
  TreasuryYieldSchema,
  UnlistedCompanySchema,
  UnlistedOverviewSchema,
  ValuationHistorySchema,
  ValuationOverviewSchema,
  ValuationSnapshotSchema,
  WatchlistItemViewSchema,
  WeightViewSchema,
  WikiEntryTypeSchema,
  WikiEntryViewSchema,
} from "./schemas";

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  createdAt: number;
  /**
   * MS-29 F4：信任锚定 payload（TrustPayload v1 的 JSON 文本）。assistant 自 trustStore 携带；
   * user 恒不带（undefined）；GET 回带 user 消息为 null（B8 Jackson ALWAYS 序列化形态）。
   * PUT 序列化仅携带非空字符串（null/undefined/空串省略键，后端口径等价 null）。
   */
  payload?: string | null;
}

// —— 行情数据 ——

export type IndexQuote = z.infer<typeof IndexQuoteSchema>;
export type MarketOverview = z.infer<typeof MarketOverviewSchema>;
export type StockHit = z.infer<typeof StockHitSchema>;
export type Quote = z.infer<typeof QuoteSchema>;
export type KlineBar = z.infer<typeof KlineBarSchema>;
export type FinancialIndicator = z.infer<typeof FinancialIndicatorSchema>;
export type Financials = z.infer<typeof FinancialsSchema>;
export type NewsItem = z.infer<typeof NewsItemSchema>;
export type Health = z.infer<typeof HealthSchema>;

// —— 市场估值 ——

export type ValuationSnapshot = z.infer<typeof ValuationSnapshotSchema>;
export type IndexValuationPoint = z.infer<typeof IndexValuationPointSchema>;
export type ValuationOverview = z.infer<typeof ValuationOverviewSchema>;
export type IndustryValuation = z.infer<typeof IndustryValuationSchema>;
export type TreasuryYieldPoint = z.infer<typeof TreasuryYieldSchema>;
export type IndexValuationSeries = z.infer<typeof IndexValuationSeriesSchema>;
export type ValuationHistory = z.infer<typeof ValuationHistorySchema>;

// —— 持仓组合 ——

// 以下四个枚举别名在 schemas.ts 内联于各自 ViewSchema（未单独导出 schema），
// 后端枚举字面量稳定，手写联合类型与 schema 等价
export type GroupType = "ACCOUNT" | "TAG";
export type TradeType = "BUY" | "SELL";
export type DividendType = "CASH" | "STOCK";
export type CashTransactionType = "DEPOSIT" | "WITHDRAW";

export type GroupView = z.infer<typeof GroupViewSchema>;
export type PositionView = z.infer<typeof PositionViewSchema>;
export type PortfolioOverview = z.infer<typeof PortfolioOverviewSchema>;
export type TradeView = z.infer<typeof TradeViewSchema>;

/** 分红记录视图：schemas.ts 暂无对应 schema（该视图无 zod 边界校验），保留手写。 */
export interface DividendView {
  id: number;
  type: DividendType;
  exDate: string;
  cashPerShare: number | null;
  stockRatio: number | null;
}

export type CashTransactionView = z.infer<typeof CashTransactionViewSchema>;
export type AllocationSlice = z.infer<typeof AllocationSliceSchema>;
export type AssetAllocation = z.infer<typeof AssetAllocationSchema>;
export type IndustrySlice = z.infer<typeof IndustrySliceSchema>;
export type IndustryDistribution = z.infer<typeof IndustryDistributionSchema>;
export type ConcentrationHolding = z.infer<typeof ConcentrationHoldingSchema>;
export type Concentration = z.infer<typeof ConcentrationSchema>;
// 删除影响预检（GET /api/portfolio/positions/{id}/delete-impact）
export type DeleteImpact = z.infer<typeof DeleteImpactSchema>;

// CSV 批量导入结果（rowErrors 空=全部成功）
export type ImportRowError = z.infer<typeof ImportRowErrorSchema>;
export type ImportResult = z.infer<typeof ImportResultSchema>;

// —— 资产配置 ——

export type AssetClass = z.infer<typeof AssetClassSchema>;
export type PlanSource = z.infer<typeof PlanSourceSchema>;
export type RebalanceFrequency = z.infer<typeof RebalanceFrequencySchema>;
export type WeightView = z.infer<typeof WeightViewSchema>;
export type TemplateView = z.infer<typeof TemplateViewSchema>;
export type PlanView = z.infer<typeof PlanViewSchema>;
export type DeviationSlice = z.infer<typeof DeviationSliceSchema>;
export type DeviationView = z.infer<typeof DeviationViewSchema>;
export type RebalanceItem = z.infer<typeof RebalanceItemSchema>;
export type RebalanceTimeTrigger = z.infer<typeof RebalanceTimeTriggerSchema>;
export type RebalanceView = z.infer<typeof RebalanceViewSchema>;
// 配置回测（MS-13 M07-F06）：数值为后端 toPlainString 字符串（曲线净值期初 1000；
// 年化收益/MDD 为小数、夏普为比率），sharpe null=不可算「—」；
// window/rebalance 回显请求值，windowStart/End 为实际截齐窗口。
export type CurvePointView = z.infer<typeof CurvePointViewSchema>;
export type BacktestView = z.infer<typeof BacktestViewSchema>;

// —— 自选观察（/api/watchlist/**）——

export type WatchlistItemView = z.infer<typeof WatchlistItemViewSchema>;
export type StockSearchHit = z.infer<typeof StockSearchHitSchema>;

export type RiskProfile = z.infer<typeof RiskProfileSchema>;
export type OptionView = z.infer<typeof OptionViewSchema>;
export type QuestionView = z.infer<typeof QuestionViewSchema>;
export type QuestionnaireView = z.infer<typeof QuestionnaireViewSchema>;
export type AssessmentView = z.infer<typeof AssessmentViewSchema>;

// —— 价值筛选（/api/screening/**，与后端 ScreeningController 的 DTO 对齐）——

export type ScreeningStock = z.infer<typeof ScreeningStockSchema>;

/** 筛选请求入参（前端表单模型，无响应 schema 可派生）：单位与换算口径见字段注释。 */
export interface ScreeningParams {
  peTtmMax?: number;
  pbMax?: number;
  dividendYieldMin?: number;
  roeMin?: number;
  roaMin?: number;
  grossMarginMin?: number;
  debtToAssetsMax?: number;
  currentRatioMin?: number;
  revenueYoyMin?: number;
  netprofitYoyMin?: number;
  /** 单位：亿元（前端用户口径，api 层换算为元） */
  totalMvMin?: number;
  turnoverRateMin?: number;
  industryCode?: string;
  /** 指数成分股范围（000300 沪深300 / 000905 中证500，白名单在后端） */
  indexCode?: string;
  sortBy?: string;
  sortDirection?: "ASC" | "DESC";
  limit?: number;
}

// —— ETF 基金筛选（/api/screening/funds，与后端 FundScreeningResult 对齐）——
// 注意三个数值字段的后端口径即用户输入口径，api 层不做换算：
// feeRate=年化 %（0.6 = 0.6%）、scale=亿元、trackingError1y=小数（0.0318 = 3.18%，收盘价口径）。

export type FundScreeningResult = z.infer<typeof FundScreeningResultSchema>;

/** 基金筛选请求入参（前端表单模型，无响应 schema 可派生）。 */
export interface FundScreeningParams {
  /** 年化费率上限，单位 %（输入 0.6 = 0.6%） */
  feeRateMax?: number;
  /** 规模下限，单位 亿元 */
  scaleMin?: number;
  /** 跟踪误差上限，小数（输入 0.05 = 5%），收盘价口径 */
  trackingErrorMax?: number;
  /** 六桶类别：宽基/行业/商品/债券/QDII/其他（白名单在后端） */
  category?: string;
  /** 可排序列：fee_rate / scale / tracking_error_1y（默认 tracking_error_1y ASC） */
  sortBy?: string;
  sortDirection?: "ASC" | "DESC";
  limit?: number;
}

// —— 投资决策记录 ——

export type JournalEntryType = z.infer<typeof JournalEntryTypeSchema>;
export type PeriodType = z.infer<typeof PeriodTypeSchema>;
export type JournalEntryView = z.infer<typeof JournalEntryViewSchema>;
export type TimelineEventType = z.infer<typeof TimelineEventTypeSchema>;
export type TimelineEventView = z.infer<typeof TimelineEventViewSchema>;

// —— 收益分析（/api/analytics/**，与后端 AnalyticsController 的 View 对齐）——
// 偏差说明：后端 OverviewView.benchmarks 是 Map<String, BenchmarkComparison>（JSON 对象、
// key=indexCode），brief 里的 BenchmarkComparison[] 数组与真实序列化不符，此处按后端修正。

export type BenchmarkComparison = z.infer<typeof BenchmarkComparisonSchema>;
export type AnalyticsOverview = z.infer<typeof AnalyticsOverviewSchema>;
export type NavPoint = z.infer<typeof NavPointSchema>;
export type IndexPoint = z.infer<typeof IndexPointSchema>;
export type AnalyticsNav = z.infer<typeof AnalyticsNavSchema>;
export type AnnualReturnRow = z.infer<typeof AnnualReturnRowSchema>;
export type TradeStatsView = z.infer<typeof TradeStatsViewSchema>;
// 数值为后端 toPlainString 字符串（如 "0.2500000000"），null=「—」；recoveryDate null=回撤进行中。
export type RiskStatsView = z.infer<typeof RiskStatsViewSchema>;
// 归因（MS-13 F09）：allocation/selection=行业累计贡献小数（toPlainString 字符串）；
// residual=totalExcess−Σ贡献（日频权重近似损耗）；窗口 null=无可归因交易日。
export type AttributionRow = z.infer<typeof AttributionRowSchema>;
export type Attribution = z.infer<typeof AttributionSchema>;

// —— 行业研究（/api/industry/**，与后端 IndustryController 的 DTO 对齐）——

export type Prosperity = z.infer<typeof ProsperitySchema>;
export type IndustryBoardItem = z.infer<typeof IndustryBoardItemSchema>;
export type IndustryStock = z.infer<typeof IndustryStockSchema>;

// —— 行业关注（/api/industry-watch/**，独立前缀需登录，与后端 IndustryWatchView 对齐）——

export type IndustryWatchItem = z.infer<typeof IndustryWatchItemSchema>;

// —— 未上市与融资（/api/industry/{code}/unlisted/**，MS-10 P2，与后端三 View 对齐）——
// 轮次双字段：latestRound/round 为 FundingRound 枚举名（排序用），*Label 为中文（渲染用）。

export type UnlistedCompany = z.infer<typeof UnlistedCompanySchema>;
export type FundingEvent = z.infer<typeof FundingEventSchema>;
export type RoundCount = z.infer<typeof RoundCountSchema>;
export type UnlistedOverview = z.infer<typeof UnlistedOverviewSchema>;

/** 策展企业单条保存命令（POST/PUT /api/industry-curation/companies，id 有值为 PUT）。 */
export interface SaveUnlistedCompanyInput {
  id?: number;
  industryCode: string;
  companyName: string;
  segment: string | null;
  latestRound: string;
  lastFundingDate: string | null;
  totalFundingYi: number | null;
  summary: string | null;
  sourceNote: string | null;
}

/** 策展 CSV 导入结果（upsert 双计数，与 MS-14 ImportResult 契约不同源）。 */
export type CurationImportResult = z.infer<typeof CurationImportResultSchema>;

// —— 产业链（/api/industry/{code}/chains，MS-10 P3，与后端 ChainView 嵌套对齐）——

export type ChainMemberView = z.infer<typeof ChainMemberViewSchema>;
export type ChainStageView = z.infer<typeof ChainStageViewSchema>;
export type ChainView = z.infer<typeof ChainViewSchema>;

/** 产业链全文档保存命令（POST/PUT /api/industry-curation/chains，id 有值为 PUT，提交体无响应 schema）。 */
export interface SaveChainInput {
  id?: number;
  name: string;
  description: string | null;
  stages: Array<{
    tier: string;
    name: string;
    sortOrder: number;
    members: Array<{
      memberType: string;
      stockCode: string | null;
      unlistedCompanyId: number | null;
      displayName: string;
    }>;
  }>;
}

// —— 投资知识库（/api/wiki/**，与后端 WikiController 的 View 对齐）——

export type WikiEntryType = z.infer<typeof WikiEntryTypeSchema>;
export type PrincipleMetric = z.infer<typeof PrincipleMetricSchema>;
export type WikiEntryView = z.infer<typeof WikiEntryViewSchema>;
export type PrincipleRuleView = z.infer<typeof PrincipleRuleViewSchema>;
