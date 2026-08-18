// 领域类型（与后端 DTO 对齐）

export interface ChatMessage {
  id: string;
  role: "user" | "assistant";
  content: string;
  createdAt: number;
}

export interface AguiRequestMessage {
  id?: string;
  role: "user" | "assistant" | "system";
  content: string;
}

export interface RunAgentInput {
  threadId: string;
  runId: string;
  messages: AguiRequestMessage[];
  state: Record<string, unknown>;
  tools: unknown[];
  forwardedProps: Record<string, unknown>;
}

/** AG-UI 事件（宽松类型 + 常用字段）。 */
export interface AguiEvent {
  type: string;
  threadId?: string;
  runId?: string;
  messageId?: string;
  toolCallId?: string;
  toolCallName?: string;
  delta?: string;
  role?: string;
  content?: unknown;
  message?: string;
  code?: string;
  name?: string;
  value?: unknown;
  [key: string]: unknown;
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
