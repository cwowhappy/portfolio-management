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
