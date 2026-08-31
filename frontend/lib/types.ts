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

// —— 持仓组合 ——

export type GroupType = "ACCOUNT" | "TAG";
export type TradeType = "BUY" | "SELL";
export type DividendType = "CASH" | "STOCK";
export type CashTransactionType = "DEPOSIT" | "WITHDRAW";

export interface GroupView {
  id: number;
  name: string;
  type: GroupType;
  positionCount: number;
  cashBalance: number;
}

export interface PositionView {
  id: number;
  groupId: number;
  stockCode: string;
  stockName: string;
  quantity: number;
  avgCost: number | null;
  price: number | null;
  marketValue: number | null;
  floatingPnl: number | null;
  pnlRatio: number | null;
  realizedPnl: number;
  totalBuyCost: number;
  cumulativeCashDividend: number;
}

export interface PortfolioOverview {
  totalAssets: number;
  totalCost: number;
  totalPnl: number;
  todayPnl: number;
  cashTotal: number;
  totalCashDividend: number;
  positionCount: number;
  groupCount: number;
}

export interface TradeView {
  id: number;
  type: TradeType;
  tradeDate: string;
  price: number;
  quantity: number;
  fee: number;
}

export interface DividendView {
  id: number;
  type: DividendType;
  exDate: string;
  cashPerShare: number | null;
  stockRatio: number | null;
}

export interface CashTransactionView {
  id: number;
  groupId: number;
  type: CashTransactionType;
  amount: number;
  txDate: string;
  note: string | null;
}

export interface AllocationSlice { category: string; marketValue: number; ratio: number; }
export interface AssetAllocation { slices: AllocationSlice[]; }
export interface IndustrySlice { industryName: string; marketValue: number; ratio: number; }
export interface IndustryDistribution { slices: IndustrySlice[]; }
export interface ConcentrationHolding { stockCode: string; stockName: string; marketValue: number; ratio: number; }
export interface Concentration { holdings: ConcentrationHolding[]; top5Ratio: number; }

// —— 资产配置 ——

export type AssetClass = "STOCK" | "BOND" | "GOLD" | "CASH" | "REITS";
export type PlanSource = "TEMPLATE" | "CUSTOM";
export interface WeightView { assetClass: AssetClass; weight: number; }
export interface TemplateView { id: string; name: string; weights: WeightView[]; }
export interface PlanView { id: number; name: string; source: PlanSource; weights: WeightView[]; active: boolean; }
export interface DeviationSlice { assetClass: AssetClass; targetWeight: number; actualWeight: number; deviation: number; }
export interface DeviationView { slices: DeviationSlice[]; }
