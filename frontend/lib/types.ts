// 领域类型（与后端 DTO 对齐）

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  createdAt: number;
}

// —— 行情数据 ——

export interface IndexQuote {
  code: string;
  name: string;
  price: number;
  change: number;
  changePct: number;
}

export interface MarketOverview {
  time: string;
  indices: IndexQuote[];
}

export interface StockHit {
  code: string;
  name: string;
  market: string;
  marketName: string;
}

export interface Quote {
  code: string;
  name: string;
  price: number;
  change: number;
  changePct: number;
  open: number;
  high: number;
  low: number;
  prevClose: number;
  volume: number;
  amount: number;
  pe: number | null;
  pb: number | null;
  time: string;
}

export interface KlineBar {
  date: string;
  open: number;
  close: number;
  high: number;
  low: number;
  volume: number;
  amount: number;
  amplitudePct: number;
}

export interface FinancialIndicator {
  reportDate: string;
  eps: number | null;
  bps: number | null;
  totalRevenue: number | null;
  netProfit: number | null;
  weightedRoe: number | null;
  grossMargin: number | null;
}

export interface Financials {
  code: string;
  name: string;
  pe: number | null;
  pb: number | null;
  indicators: FinancialIndicator[];
}

export interface NewsItem {
  title: string;
  summary: string;
  source: string;
  date: string;
  url: string;
}

export interface Health {
  status: "up" | "degraded";
  llm: { provider: string; model: string; baseUrl: string; keyConfigured: boolean };
  market: { ok: boolean; latencyMs?: number; message?: string };
}

// —— 市场估值 ——

export interface ValuationSnapshot {
  tradingDay: string;
  peMedian: number;
  pbMedian: number;
  netBreakerCount: number;
  netBreakerRatio: number;
}

export interface IndexValuationPoint {
  indexCode: string;
  indexName: string;
  pe: number | null;
  pb: number | null;
  dividendYield: number | null;
  pePercentile: number | null;
  pbPercentile: number | null;
}

export interface ValuationOverview {
  latestSnapshot: ValuationSnapshot | null;
  pePercentile: number | null;
  pbPercentile: number | null;
  netBreakerPercentile: number | null;
  erp: number | null;
  erpPercentile: number | null;
  thermometer: number | null;
  indices: IndexValuationPoint[];
  dataAccumulating: boolean;
}

export interface IndustryValuation {
  industryCode: string;
  industryName: string;
  pe: number | null;
  pb: number | null;
  roe: number | null;
  dividendYield: number | null;
}

export interface TreasuryYieldPoint {
  tradingDay: string;
  yield10y: number;
}

export interface IndexValuationSeries {
  tradingDay: string;
  indexCode: string;
  indexName: string;
  pe: number | null;
  pb: number | null;
  dividendYield: number | null;
}

export interface ValuationHistory {
  snapshots: ValuationSnapshot[];
  treasuryYields: TreasuryYieldPoint[];
  indexValuations: IndexValuationSeries[];
}
