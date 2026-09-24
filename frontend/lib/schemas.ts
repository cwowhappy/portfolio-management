// 行情响应运行时校验（zod）：在 lib/api 边界校验后端 DTO，schema drift 在边界报错而非深渲染崩溃。

import { z } from "zod";

export const QuoteSchema = z.object({
  code: z.string(),
  name: z.string(),
  price: z.number(),
  change: z.number(),
  changePct: z.number(),
  open: z.number(),
  high: z.number(),
  low: z.number(),
  prevClose: z.number(),
  volume: z.number(),
  amount: z.number(),
  pe: z.number().nullable(),
  pb: z.number().nullable(),
  time: z.string(),
});

export const KlineBarSchema = z.object({
  date: z.string(),
  open: z.number(),
  close: z.number(),
  high: z.number(),
  low: z.number(),
  volume: z.number(),
  amount: z.number(),
  amplitudePct: z.number(),
});

export const FinancialIndicatorSchema = z.object({
  reportDate: z.string(),
  eps: z.number().nullable(),
  bps: z.number().nullable(),
  totalRevenue: z.number().nullable(),
  netProfit: z.number().nullable(),
  weightedRoe: z.number().nullable(),
  grossMargin: z.number().nullable(),
});

export const FinancialsSchema = z.object({
  code: z.string(),
  name: z.string(),
  pe: z.number().nullable(),
  pb: z.number().nullable(),
  indicators: z.array(FinancialIndicatorSchema),
});

export const NewsItemSchema = z.object({
  title: z.string(),
  summary: z.string(),
  source: z.string(),
  date: z.string(),
  url: z.string(),
});

export const StockHitSchema = z.object({
  code: z.string(),
  name: z.string(),
  market: z.string(),
  marketName: z.string(),
});

export const IndexQuoteSchema = z.object({
  code: z.string(),
  name: z.string(),
  price: z.number(),
  change: z.number(),
  changePct: z.number(),
});

export const MarketOverviewSchema = z.object({
  time: z.string(),
  indices: z.array(IndexQuoteSchema),
});

export const HealthSchema = z.object({
  status: z.enum(["up", "degraded"]),
  llm: z.object({
    provider: z.string(),
    model: z.string(),
    baseUrl: z.string(),
    keyConfigured: z.boolean(),
  }),
  market: z.object({
    ok: z.boolean(),
    latencyMs: z.number().optional(),
    message: z.string().optional(),
  }),
});

// —— 市场估值（/api/valuation/**，与后端 ValuationController 的 DTO 对齐）——

export const ValuationSnapshotSchema = z.object({
  tradingDay: z.string(),
  peMedian: z.number(),
  pbMedian: z.number(),
  netBreakerCount: z.number(),
  netBreakerRatio: z.number(),
});

export const IndexValuationPointSchema = z.object({
  indexCode: z.string(),
  indexName: z.string(),
  pe: z.number().nullable(),
  pb: z.number().nullable(),
  dividendYield: z.number().nullable(),
  pePercentile: z.number().nullable(),
  pbPercentile: z.number().nullable(),
});

export const ValuationOverviewSchema = z.object({
  latestSnapshot: ValuationSnapshotSchema.nullable(),
  pePercentile: z.number().nullable(),
  pbPercentile: z.number().nullable(),
  netBreakerPercentile: z.number().nullable(),
  erp: z.number().nullable(),
  erpPercentile: z.number().nullable(),
  thermometer: z.number().nullable(),
  indices: z.array(IndexValuationPointSchema),
  dataAccumulating: z.boolean(),
});

export const IndustryValuationSchema = z.object({
  industryCode: z.string(),
  industryName: z.string(),
  pe: z.number().nullable(),
  pb: z.number().nullable(),
  roe: z.number().nullable(),
  dividendYield: z.number().nullable(),
});

export const TreasuryYieldSchema = z.object({
  tradingDay: z.string(),
  yield10y: z.number(),
});

export const IndexValuationSeriesSchema = z.object({
  tradingDay: z.string(),
  indexCode: z.string(),
  indexName: z.string(),
  pe: z.number().nullable(),
  pb: z.number().nullable(),
  dividendYield: z.number().nullable(),
});

export const ValuationHistorySchema = z.object({
  snapshots: z.array(ValuationSnapshotSchema),
  treasuryYields: z.array(TreasuryYieldSchema),
  indexValuations: z.array(IndexValuationSeriesSchema),
});

// —— 持仓组合（/api/portfolio/**，与后端 PortfolioController 的 DTO 对齐）——

export const GroupViewSchema = z.object({
  id: z.number(), name: z.string(), type: z.enum(["ACCOUNT", "TAG"]),
  positionCount: z.number(), cashBalance: z.number(),
});

export const PositionViewSchema = z.object({
  id: z.number(), groupId: z.number(), stockCode: z.string(), stockName: z.string(),
  quantity: z.number(), avgCost: z.number().nullable(), price: z.number().nullable(),
  marketValue: z.number().nullable(), floatingPnl: z.number().nullable(),
  pnlRatio: z.number().nullable(), realizedPnl: z.number(),
  totalBuyCost: z.number(), cumulativeCashDividend: z.number(),
});

export const TradeViewSchema = z.object({
  id: z.number(), type: z.enum(["BUY", "SELL"]), tradeDate: z.string(),
  price: z.number(), quantity: z.number(), fee: z.number(),
});

export const CashTransactionViewSchema = z.object({
  id: z.number(), groupId: z.number(), type: z.enum(["DEPOSIT", "WITHDRAW"]),
  amount: z.number(), txDate: z.string(), note: z.string().nullable(),
});

export const PortfolioOverviewSchema = z.object({
  totalAssets: z.number(), totalCost: z.number(), totalPnl: z.number(),
  todayPnl: z.number(), cashTotal: z.number(), totalCashDividend: z.number(),
  positionCount: z.number(), groupCount: z.number(),
});

export const AllocationSliceSchema = z.object({ category: z.string(), marketValue: z.number(), ratio: z.number() });
export const AssetAllocationSchema = z.object({ slices: z.array(AllocationSliceSchema) });
export const IndustrySliceSchema = z.object({ industryName: z.string(), marketValue: z.number(), ratio: z.number() });
export const IndustryDistributionSchema = z.object({ slices: z.array(IndustrySliceSchema) });
export const ConcentrationHoldingSchema = z.object({ stockCode: z.string(), stockName: z.string(), marketValue: z.number(), ratio: z.number() });
export const ConcentrationSchema = z.object({ holdings: z.array(ConcentrationHoldingSchema), top5Ratio: z.number() });

// —— 资产配置（/api/allocation/**，与后端 AllocationController 的 DTO 对齐）——

export const AssetClassSchema = z.enum(["STOCK", "BOND", "GOLD", "CASH", "REITS"]);
export const PlanSourceSchema = z.enum(["TEMPLATE", "CUSTOM", "ASSESSMENT"]);
export const WeightViewSchema = z.object({ assetClass: AssetClassSchema, weight: z.number() });
export const TemplateViewSchema = z.object({ id: z.string(), name: z.string(), weights: z.array(WeightViewSchema) });
export const RebalanceFrequencySchema = z.enum(["OFF", "QUARTERLY", "SEMIANNUAL"]);
export const PlanViewSchema = z.object({
  id: z.number(), name: z.string(), source: PlanSourceSchema,
  weights: z.array(WeightViewSchema), active: z.boolean(),
  rebalanceFrequency: RebalanceFrequencySchema, lastRebalancedAt: z.string().nullable(),
});
export const DeviationSliceSchema = z.object({
  assetClass: AssetClassSchema, targetWeight: z.number(), actualWeight: z.number(), deviation: z.number(),
});
export const DeviationViewSchema = z.object({ slices: z.array(DeviationSliceSchema) });
export const RebalanceItemSchema = z.object({
  assetClass: AssetClassSchema, targetWeight: z.number(), actualWeight: z.number(), deviation: z.number(),
  targetAmount: z.number(), currentAmount: z.number(), suggestedAmount: z.number(),
  thresholdBreached: z.boolean(),
});
export const RebalanceTimeTriggerSchema = z.object({
  frequency: RebalanceFrequencySchema, anchorDate: z.string().nullable(), dueDate: z.string().nullable(),
  daysOverdue: z.number(), triggered: z.boolean(),
});
export const RebalanceViewSchema = z.object({
  hasActivePlan: z.boolean(), totalAssets: z.number(), suppressed: z.boolean(),
  items: z.array(RebalanceItemSchema), timeTrigger: RebalanceTimeTriggerSchema.nullable(),
  anyAlert: z.boolean(),
});

export const RiskProfileSchema = z.enum(["CONSERVATIVE", "STABLE", "BALANCED", "GROWTH", "AGGRESSIVE"]);
export const OptionViewSchema = z.object({ id: z.string(), text: z.string() });
export const QuestionViewSchema = z.object({
  id: z.string(), dimension: z.string(), text: z.string(), options: z.array(OptionViewSchema),
});
export const QuestionnaireViewSchema = z.object({ questions: z.array(QuestionViewSchema) });
export const AssessmentViewSchema = z.object({
  totalScore: z.number(), profile: RiskProfileSchema, profileName: z.string(),
  weights: z.array(WeightViewSchema), answers: z.record(z.string()), assessedAt: z.string(),
});

// —— 价值筛选（/api/screening/**，与后端 ScreeningController 的 DTO 对齐）——

export const ScreeningStockSchema = z.object({
  stockCode: z.string(),
  stockName: z.string(),
  industryCode: z.string().nullable(),
  industryName: z.string().nullable(),
  peTtm: z.number().nullable(),
  pb: z.number().nullable(),
  dividendYield: z.number().nullable(),
  roe: z.number().nullable(),
  roa: z.number().nullable(),
  grossMargin: z.number().nullable(),
  debtToAssets: z.number().nullable(),
  currentRatio: z.number().nullable(),
  revenueYoy: z.number().nullable(),
  netprofitYoy: z.number().nullable(),
  totalMv: z.number().nullable(),
  turnoverRate: z.number().nullable(),
});

// —— 自选观察（/api/watchlist/**，与后端 WatchlistController 的 DTO 对齐）——

export const WatchlistItemViewSchema = z.object({
  stockCode: z.string(),
  stockName: z.string().nullable(),
  industryName: z.string().nullable(),
  price: z.number().nullable(),
  peTtm: z.number().nullable(),
  pb: z.number().nullable(),
  dividendYield: z.number().nullable(),
  totalMv: z.number().nullable(),
  addedAt: z.string(),
});

export const StockSearchHitSchema = z.object({
  stockCode: z.string(),
  stockName: z.string(),
  industryName: z.string().nullable(),
  peTtm: z.number().nullable(),
  pb: z.number().nullable(),
  totalMv: z.number().nullable(),
});

// —— 投资决策记录（/api/journal/**，与后端 JournalController 的 DTO 对齐）——

export const JournalEntryTypeSchema = z.enum(["BUY_MEMO", "SELL_MEMO", "RESEARCH_NOTE", "REVIEW"]);
export const PeriodTypeSchema = z.enum(["QUARTERLY", "ANNUAL"]);
export const JournalEntryViewSchema = z.object({
  id: z.number(),
  type: JournalEntryTypeSchema,
  stockCode: z.string().nullable(),
  stockName: z.string().nullable(),
  tradeId: z.number().nullable(),
  title: z.string(),
  content: z.string(),
  targetPrice: z.number().nullable(),
  stopLoss: z.number().nullable(),
  periodType: PeriodTypeSchema.nullable(),
  periodStart: z.string().nullable(),
  periodEnd: z.string().nullable(),
  eventDate: z.string(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export const TimelineEventTypeSchema = z.enum([
  "BUY", "SELL", "DIVIDEND", "BUY_MEMO", "SELL_MEMO", "RESEARCH_NOTE", "REVIEW",
]);
export const TimelineEventViewSchema = z.object({
  type: TimelineEventTypeSchema,
  date: z.string(),
  title: z.string(),
  description: z.string(),
  stockCode: z.string().nullable(),
  stockName: z.string().nullable(),
  refId: z.number().nullable(),
  refType: z.string(),
});

// —— 收益分析（/api/analytics/**，与后端 AnalyticsController 的 View 对齐）——

export const BenchmarkComparisonSchema = z.object({
  indexCode: z.string(), indexName: z.string(), twr: z.number(), excess: z.number(),
});
export const AnalyticsOverviewSchema = z.object({
  totalValue: z.number(), twrCumulative: z.number(), twrAnnualized: z.number(),
  irr: z.number().nullable(), irrSimple: z.boolean(), windowDays: z.number(),
  benchmarks: z.record(BenchmarkComparisonSchema),
});
export const NavPointSchema = z.object({ date: z.string(), totalValue: z.number() });
export const IndexPointSchema = z.object({ date: z.string(), close: z.number() });
export const AnalyticsNavSchema = z.object({
  windowStart: z.string(), windowEnd: z.string(),
  points: z.array(NavPointSchema), benchmarks: z.record(z.array(IndexPointSchema)),
});
export const AnnualReturnRowSchema = z.object({
  year: z.number(), portfolioTwr: z.number(),
  benchmarkTwr: z.record(z.number()), excess: z.record(z.number()),
});
export const TradeStatsViewSchema = z.object({
  sellCount: z.number(), winCount: z.number(), winRate: z.string(), avgWin: z.string(),
  avgLoss: z.string(), profitFactor: z.string().nullable(), avgHoldingDays: z.string(),
  bestPnl: z.string(), worstPnl: z.string(),
});
// 数值字段对齐后端 toPlainString 契约（字符串、null=「—」）；recoveryDate null=回撤进行中；
// sharpeRfFallback=true 表示 rf 端口空表、夏普按 rf=0 退化口径计算。
export const RiskStatsViewSchema = z.object({
  mdd: z.string().nullable(), currentDrawdown: z.string().nullable(),
  peakDate: z.string().nullable(), troughDate: z.string().nullable(), recoveryDate: z.string().nullable(),
  drawdownDays: z.number(), sharpe: z.string().nullable(), sharpeRfFallback: z.boolean(),
  calmar: z.string().nullable(), windowDays: z.number(),
});
// 归因（MS-13 F09）：数值 toPlainString 小数（累计贡献）；窗口 null=无可归因交易日；
// industryName null=映射缺失桶，展示用行业码兜底。
export const AttributionRowSchema = z.object({
  industry: z.string(), industryName: z.string().nullable(),
  allocation: z.string(), selection: z.string(),
});
export const AttributionSchema = z.object({
  windowStart: z.string().nullable(), windowEnd: z.string().nullable(),
  rows: z.array(AttributionRowSchema),
  cashAllocation: z.string(), totalExcess: z.string(),
  residual: z.string(), unmappedValueShare: z.string(),
});

// —— 行业研究（/api/industry/**，与后端 IndustryController 的 DTO 对齐）——

export const ProsperitySchema = z.enum(["UP", "FLAT", "DOWN"]);

export const IndustryBoardItemSchema = z.object({
  industryCode: z.string(),
  industryName: z.string(),
  pe: z.number().nullable(),
  pb: z.number().nullable(),
  roe: z.number().nullable(),
  dividendYield: z.number().nullable(),
  pePercentile: z.number().nullable(),
  pbPercentile: z.number().nullable(),
  prosperity: ProsperitySchema.nullable(),
  prosperityInputs: z
    .object({
      roeDeltaMedian: z.number().nullable(),
      revenueYoyMedian: z.number().nullable(),
      sampleSize: z.number(),
    })
    .nullable(),
});

export const IndustryStockSchema = z.object({
  stockCode: z.string(),
  stockName: z.string(),
  totalMv: z.number().nullable(),
  revenue: z.number().nullable(),
  revenueReportDate: z.string().nullable(),
  roe: z.number().nullable(),
  peTtm: z.number().nullable(),
  pb: z.number().nullable(),
  dividendYield: z.number().nullable(),
  prosperity: ProsperitySchema.nullable(),
});

// —— 投资知识库（/api/wiki/**，与后端 WikiController 的 View 对齐）——

export const WikiEntryTypeSchema = z.enum(["BOOK_NOTE", "CONCEPT", "RESEARCH_NOTE"]);
export const PrincipleMetricSchema = z.enum([
  "SINGLE_POSITION_RATIO", "INDUSTRY_POSITION_RATIO", "STOCK_PE_MAX", "STOCK_PB_MAX",
]);
export const WikiEntryViewSchema = z.object({
  id: z.number(),
  type: WikiEntryTypeSchema,
  title: z.string(),
  content: z.string(),
  category: z.string().nullable(),
  industryCode: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
export const PrincipleRuleViewSchema = z.object({
  id: z.number(),
  metric: PrincipleMetricSchema,
  threshold: z.number(),
  enabled: z.boolean(),
  description: z.string().nullable(),
  createdAt: z.string(),
  updatedAt: z.string(),
});
