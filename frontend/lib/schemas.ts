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
export const PlanSourceSchema = z.enum(["TEMPLATE", "CUSTOM"]);
export const WeightViewSchema = z.object({ assetClass: AssetClassSchema, weight: z.number() });
export const TemplateViewSchema = z.object({ id: z.string(), name: z.string(), weights: z.array(WeightViewSchema) });
export const PlanViewSchema = z.object({
  id: z.number(), name: z.string(), source: PlanSourceSchema,
  weights: z.array(WeightViewSchema), active: z.boolean(),
});
export const DeviationSliceSchema = z.object({
  assetClass: AssetClassSchema, targetWeight: z.number(), actualWeight: z.number(), deviation: z.number(),
});
export const DeviationViewSchema = z.object({ slices: z.array(DeviationSliceSchema) });
