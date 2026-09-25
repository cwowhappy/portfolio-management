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

// CSV 批量导入结果（rowErrors 空=全部成功）
export interface ImportRowError { row: number; reason: string; }
export interface ImportResult { importedCount: number; rowErrors: ImportRowError[]; }

// —— 资产配置 ——

export type AssetClass = "STOCK" | "BOND" | "GOLD" | "CASH" | "REITS";
export type PlanSource = "TEMPLATE" | "CUSTOM" | "ASSESSMENT";
export type RebalanceFrequency = "OFF" | "QUARTERLY" | "SEMIANNUAL";
export interface WeightView { assetClass: AssetClass; weight: number; }
export interface TemplateView { id: string; name: string; weights: WeightView[]; }
export interface PlanView {
  id: number; name: string; source: PlanSource; weights: WeightView[]; active: boolean;
  rebalanceFrequency: RebalanceFrequency; lastRebalancedAt: string | null;
}
export interface DeviationSlice { assetClass: AssetClass; targetWeight: number; actualWeight: number; deviation: number; }
export interface DeviationView { slices: DeviationSlice[]; }
export interface RebalanceItem {
  assetClass: AssetClass; targetWeight: number; actualWeight: number; deviation: number;
  targetAmount: number; currentAmount: number; suggestedAmount: number; thresholdBreached: boolean;
}
export interface RebalanceTimeTrigger {
  frequency: RebalanceFrequency; anchorDate: string | null; dueDate: string | null;
  daysOverdue: number; triggered: boolean;
}
export interface RebalanceView {
  hasActivePlan: boolean; totalAssets: number; suppressed: boolean; anyAlert: boolean;
  items: RebalanceItem[]; timeTrigger: RebalanceTimeTrigger | null;
}
// 配置回测（MS-13 M07-F06）：数值为后端 toPlainString 字符串（曲线净值期初 1000；
// 年化收益/MDD 为小数、夏普为比率），sharpe null=不可算「—」；
// window/rebalance 回显请求值，windowStart/End 为实际截齐窗口。
export interface CurvePointView { date: string; value: string; }
export interface BacktestView {
  planName: string; windowStart: string; windowEnd: string;
  window: string; rebalance: string; curve: CurvePointView[];
  annualizedReturn: string; mdd: string; sharpe: string | null; rfFallback: boolean;
}

// —— 自选观察（/api/watchlist/**）——

export interface WatchlistItemView {
  stockCode: string; stockName: string | null; industryName: string | null; price: number | null;
  peTtm: number | null; pb: number | null; dividendYield: number | null; totalMv: number | null;
  addedAt: string;
}
export interface StockSearchHit {
  stockCode: string; stockName: string; industryName: string | null;
  peTtm: number | null; pb: number | null; totalMv: number | null;
}

export type RiskProfile = "CONSERVATIVE" | "STABLE" | "BALANCED" | "GROWTH" | "AGGRESSIVE";
export interface OptionView { id: string; text: string; }
export interface QuestionView { id: string; dimension: string; text: string; options: OptionView[]; }
export interface QuestionnaireView { questions: QuestionView[]; }
export interface AssessmentView {
  totalScore: number; profile: RiskProfile; profileName: string;
  weights: WeightView[]; answers: Record<string, string>; assessedAt: string;
}

// —— 价值筛选（/api/screening/**，与后端 ScreeningController 的 DTO 对齐）——

export interface ScreeningStock {
  stockCode: string;
  stockName: string;
  industryCode: string | null;
  industryName: string | null;
  peTtm: number | null;
  pb: number | null;
  dividendYield: number | null;
  roe: number | null;
  roa: number | null;
  grossMargin: number | null;
  debtToAssets: number | null;
  currentRatio: number | null;
  revenueYoy: number | null;
  netprofitYoy: number | null;
  totalMv: number | null;
  turnoverRate: number | null;
}

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

// —— 投资决策记录 ——

export type JournalEntryType = "BUY_MEMO" | "SELL_MEMO" | "RESEARCH_NOTE" | "REVIEW";
export type PeriodType = "QUARTERLY" | "ANNUAL";
export interface JournalEntryView {
  id: number;
  type: JournalEntryType;
  stockCode: string | null;
  stockName: string | null;
  tradeId: number | null;
  title: string;
  content: string;
  targetPrice: number | null;
  stopLoss: number | null;
  periodType: PeriodType | null;
  periodStart: string | null;
  periodEnd: string | null;
  eventDate: string;
  createdAt: string;
  updatedAt: string;
}
export type TimelineEventType = "BUY" | "SELL" | "DIVIDEND" | "BUY_MEMO" | "SELL_MEMO" | "RESEARCH_NOTE" | "REVIEW";
export interface TimelineEventView {
  type: TimelineEventType;
  date: string;
  title: string;
  description: string;
  stockCode: string | null;
  stockName: string | null;
  refId: number | null;
  refType: string;
}

// —— 收益分析（/api/analytics/**，与后端 AnalyticsController 的 View 对齐）——
// 偏差说明：后端 OverviewView.benchmarks 是 Map<String, BenchmarkComparison>（JSON 对象、
// key=indexCode），brief 里的 BenchmarkComparison[] 数组与真实序列化不符，此处按后端修正。

export interface BenchmarkComparison { indexCode: string; indexName: string; twr: number; excess: number }
export interface AnalyticsOverview {
  totalValue: number; twrCumulative: number; twrAnnualized: number; irr: number | null;
  irrSimple: boolean; // true=无外部现金流退化口径（irr=累计收益率，spec §三-B）
  windowDays: number; benchmarks: Record<string, BenchmarkComparison>;
}
export interface NavPoint { date: string; totalValue: number }
export interface IndexPoint { date: string; close: number }
export interface AnalyticsNav {
  windowStart: string; windowEnd: string;
  points: NavPoint[]; benchmarks: Record<string, IndexPoint[]>;
}
export interface AnnualReturnRow {
  year: number; portfolioTwr: number; benchmarkTwr: Record<string, number>; excess: Record<string, number>;
}
export interface TradeStatsView {
  sellCount: number; winCount: number; winRate: string; avgWin: string; avgLoss: string;
  profitFactor: string | null; avgHoldingDays: string; bestPnl: string; worstPnl: string;
}
// 数值为后端 toPlainString 字符串（如 "0.2500000000"），null=「—」；recoveryDate null=回撤进行中。
export interface RiskStatsView {
  mdd: string | null; currentDrawdown: string | null;
  peakDate: string | null; troughDate: string | null; recoveryDate: string | null;
  drawdownDays: number; sharpe: string | null; sharpeRfFallback: boolean;
  calmar: string | null; windowDays: number;
}
// 归因（MS-13 F09）：allocation/selection=行业累计贡献小数（toPlainString 字符串）；
// residual=totalExcess−Σ贡献（日频权重近似损耗）；窗口 null=无可归因交易日。
export interface AttributionRow {
  industry: string; industryName: string | null; allocation: string; selection: string;
}
export interface Attribution {
  windowStart: string | null; windowEnd: string | null; rows: AttributionRow[];
  cashAllocation: string; totalExcess: string; residual: string; unmappedValueShare: string;
}

// —— 行业研究（/api/industry/**，与后端 IndustryController 的 DTO 对齐）——

export type Prosperity = "UP" | "FLAT" | "DOWN";

export interface IndustryBoardItem {
  industryCode: string;
  industryName: string;
  pe: number | null; pb: number | null; roe: number | null; dividendYield: number | null;
  pePercentile: number | null; pbPercentile: number | null;
  prosperity: Prosperity | null;
  prosperityInputs: { roeDeltaMedian: number | null; revenueYoyMedian: number | null; sampleSize: number } | null;
}

export interface IndustryStock {
  stockCode: string; stockName: string;
  totalMv: number | null; revenue: number | null; revenueReportDate: string | null;
  roe: number | null; peTtm: number | null; pb: number | null; dividendYield: number | null;
  prosperity: Prosperity | null;
}

// —— 行业关注（/api/industry-watch/**，独立前缀需登录，与后端 IndustryWatchView 对齐）——

export interface IndustryWatchItem {
  industryCode: string;
  addedAt: string;
}

// —— 投资知识库（/api/wiki/**，与后端 WikiController 的 View 对齐）——

export type WikiEntryType = "BOOK_NOTE" | "CONCEPT" | "RESEARCH_NOTE";
export type PrincipleMetric =
  | "SINGLE_POSITION_RATIO"
  | "INDUSTRY_POSITION_RATIO"
  | "STOCK_PE_MAX"
  | "STOCK_PB_MAX";

export interface WikiEntryView {
  id: number;
  type: WikiEntryType;
  title: string;
  content: string;
  category: string | null;
  industryCode: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface PrincipleRuleView {
  id: number;
  metric: PrincipleMetric;
  threshold: number;
  enabled: boolean;
  description: string | null;
  createdAt: string;
  updatedAt: string;
}
