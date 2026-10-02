"use client";
// 图表渲染器注册集合（05 §4.3，2026-09-11 澄清 + P4 补 get_financials：四个有真实数据源的工具）。
// 不传 agentId：headless 无 ChatConfigurationProvider，useRenderTool 匹配链中「无 agentId 的具名渲染器」
// 仍压过通配（05 §4.1 源码核实）。注意与 useInterrupt 不同——后者内部经 useAgent 解析 agentId，
// 缺省会抛「Agent 'default' not found」（ThreadArea.tsx:391 注释），渲染 hook 无此问题。
// builder 单点 cast：buildCandlestickOption 等只收 CandlestickSpec 等窄类型，直传撞
// ChartCardBuilder(spec: ChartSpec) 的严格函数参数逆变（TS2322）；ChartCard 内部经
// ChartSpecSchema 判别联合分发，spec.type 必与 builder 匹配，cast 安全。
// get_financials 走 table 分支，不传 builder。
import { useRenderTool } from "@copilotkit/react-core/v2";
import { useMemo } from "react";
import { z } from "zod";
import { ChartCard, type ChartCardBuilder } from "@/components/chat/charts/ChartCard";
import DraftCard from "@/components/chat/DraftCard";
import { buildCandlestickOption, buildLineOption, buildBarOption } from "@/components/charts/optionBuilders";
import { KlineParamsSchema, ValuationParamsSchema, OverviewParamsSchema, FinancialsParamsSchema, ScreeningParamsSchema, FinancialsTrendParamsSchema, IndustryParamsSchema, PortfolioParamsSchema, AllocationParamsSchema, SearchNewsParamsSchema, SearchAnnouncementsParamsSchema } from "@/lib/tool-params";
import { extractResearchDraftJson, type ResearchDraft } from "@/lib/research-draft";
import { buildPieOption } from "@/components/charts/optionBuilders";

// P2 接通（D9：落库=用户确认，D20：未保存不自动暂存）：STRATEGY 草稿「保存到项目」→
// saveStrategyDraft。对话上下文暂无「会话↔研究项目」绑定（P3 上下文注入），无法定位目标
// 项目 id → 引导先立项（DraftCard 行内提示 + 前往研究页链接）；其余阶段草稿的落库端点属
// P3/P4（建仓计划/复盘），先提示到项目页记录。
async function saveResearchDraft(draft: ResearchDraft): Promise<void> {
  if (draft.stage !== "STRATEGY") {
    throw new Error("当前仅支持保存「策略」草稿，其余阶段请在研究项目页对应分区记录");
  }
  throw new Error("请先在研究页立项");
}

// ===== MS-20（Task 12）：search_news 新闻检索列表卡 =====
// 后端契约：{"items":[{title,summary,direction,importance,keyNumbers,stockCodes,url,publishedAt}],"total":n}，
// 空结果 {"items":[],"message":"该条件下暂无情报（新闻仅保留 90 天内）"}，错误 {"error","hint"}。
// 方向徽标取情报语义配色（BULLISH=利好→绿 / BEARISH=利空→红 / NEUTRAL=中性→灰），与 A股
// 红涨绿跌的行情色相反：绿/红仅复用 --color-down / --color-up 的色值以随深浅主题联动。
const NEWS_DIRECTION_LABEL: Record<string, string> = {
  BULLISH: "利好",
  BEARISH: "利空",
  NEUTRAL: "中性",
};
const NEWS_DIRECTION_CLASS: Record<string, string> = {
  BULLISH: "border-[color:var(--color-down)] text-[color:var(--color-down)]",
  BEARISH: "border-[color:var(--color-up)] text-[color:var(--color-up)]",
  NEUTRAL: "border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]",
};

interface NewsItem {
  title?: string | null;
  summary?: string | null;
  direction?: string | null;
  importance?: number | null;
  keyNumbers?: string[] | null;
  stockCodes?: string[] | null;
  url?: string | null;
  publishedAt?: string | null;
}

/** Instant（UTC ISO）→ 上海时区 MM-DD HH:mm（与后端日界折算口径一致）；非法值原样返回。 */
function formatPublished(iso?: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("zh-CN", {
    timeZone: "Asia/Shanghai",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

function NewsListCard({ status, result }: {
  status: "inProgress" | "executing" | "complete";
  result?: string;
}) {
  const parsed = useMemo(() => {
    if (status !== "complete" || typeof result !== "string") return null;
    let json: unknown;
    try {
      json = JSON.parse(result);
    } catch {
      return { degrade: true as const }; // 非 JSON 文本：折叠原始结果（照 ChartCard 降级形态）
    }
    if (json == null || typeof json !== "object" || Array.isArray(json)) return { degrade: true as const };
    const body = json as { items?: unknown; message?: unknown; total?: unknown; error?: unknown };
    if (body.error != null) return { degrade: true as const };
    if (!Array.isArray(body.items)) return { degrade: true as const };
    return {
      items: body.items as NewsItem[],
      message: typeof body.message === "string" ? body.message : null,
      total: typeof body.total === "number" ? body.total : null,
    };
  }, [status, result]);

  if (parsed == null)
    return (
      <div className="tool-card running my-2 w-full max-w-[560px] px-3 py-2 text-xs text-[color:var(--color-ink-faint)]">
        search_news 执行中…
      </div>
    );
  if (parsed.degrade)
    return (
      <details className="tool-card my-2 w-full max-w-[560px] px-3 py-2 text-xs">
        <summary className="cursor-pointer text-[color:var(--color-ink-dim)]">数据异常（原始结果折叠）</summary>
        <pre className="mt-2 max-h-[320px] overflow-auto whitespace-pre-wrap break-all text-[color:var(--color-ink-faint)]">
          {result!.slice(0, 2000)}
        </pre>
      </details>
    );
  return (
    <div className="tool-card my-2 w-full max-w-[560px] px-3.5 py-2.5" data-testid="news-list-card">
      <div className="mb-1 flex items-center justify-between text-xs">
        <span className="font-medium text-[color:var(--color-ink-dim)]">新闻检索</span>
        {parsed.total != null && (
          <span className="tabular text-[color:var(--color-ink-faint)]">共 {parsed.total} 条</span>
        )}
      </div>
      {parsed.message ? (
        <div className="py-1 text-xs text-[color:var(--color-ink-faint)]">{parsed.message}</div>
      ) : (
        <ul className="divide-y divide-[color:var(--color-line-soft)]">
          {parsed.items.map((it, i) => {
            const dirLabel = it.direction ? NEWS_DIRECTION_LABEL[it.direction] : undefined;
            const time = formatPublished(it.publishedAt);
            return (
              <li key={it.url ?? it.title ?? i} className="py-2">
                <div className="flex items-start gap-2">
                  {it.url ? (
                    <a
                      href={it.url}
                      target="_blank"
                      rel="noreferrer"
                      className="min-w-0 flex-1 truncate text-[13px] text-[color:var(--color-accent)] hover:underline"
                    >
                      {it.title ?? "(无标题)"}
                    </a>
                  ) : (
                    <span className="min-w-0 flex-1 truncate text-[13px] text-[color:var(--color-ink)]">
                      {it.title ?? "(无标题)"}
                    </span>
                  )}
                  {dirLabel && (
                    <span
                      className={
                        "shrink-0 rounded border px-1.5 py-0.5 text-[10px] leading-none " +
                        (NEWS_DIRECTION_CLASS[it.direction!] ?? NEWS_DIRECTION_CLASS.NEUTRAL)
                      }
                    >
                      {dirLabel}
                    </span>
                  )}
                  {it.importance != null && (
                    <span className="tabular shrink-0 text-[11px] text-[color:var(--color-ink-faint)]">
                      重要度 {it.importance}
                    </span>
                  )}
                </div>
                {it.summary && (
                  <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-[color:var(--color-ink-dim)]">
                    {it.summary}
                  </p>
                )}
                {(time || (it.stockCodes?.length ?? 0) > 0 || (it.keyNumbers?.length ?? 0) > 0) && (
                  <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] text-[color:var(--color-ink-faint)]">
                    {time && <span className="tabular">{time}</span>}
                    {(it.stockCodes?.length ?? 0) > 0 && <span>{it.stockCodes!.join(" / ")}</span>}
                    {(it.keyNumbers?.length ?? 0) > 0 && <span>{it.keyNumbers!.join(" / ")}</span>}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

// ===== MS-21（P2 Task 8）：search_announcements 公告检索列表卡 =====
// 后端契约：{"items":[{title,stockCode,stockName,annTypes[],annTypeSource,metrics,pdfUrl,publishedAt}],"total":n}，
// 空结果 {"items":[],"message":"..."}（scope 空集引导语 / 该标的无公告 / 检索无结果），
// 错误 {"error","hint"}。类型徽标链与后端推送卡片同款：annTypes 中文（剔 OTHER）→ annTypeSource → 「其他」。
const ANN_TYPE_LABEL: Record<string, string> = {
  INCREASE_HOLD: "股东增持",
  DECREASE_HOLD: "股东减持",
  BUYBACK: "股份回购",
  PLACEMENT: "定增配股",
  RELATED_TRANSACTION: "关联交易",
  EARNINGS_FORECAST: "业绩预告",
  EARNINGS_FLASH: "业绩快报",
  PERIODIC_REPORT: "定期报告",
  EQUITY_INCENTIVE: "股权激励",
  DELISTING_RISK: "退市风险",
  OTHER: "其他",
};
const SCOPE_LABEL: Record<string, string> = {
  subscription: "订阅范围",
  holdings: "持仓范围",
};

interface AnnouncementItem {
  title?: string | null;
  stockCode?: string | null;
  stockName?: string | null;
  annTypes?: string[] | null;
  annTypeSource?: string | null;
  metrics?: {
    revenueYi?: number | null;
    netProfitYi?: number | null;
    netProfitYoyPct?: number | null;
    deductedProfitYi?: number | null;
    grossMarginPct?: number | null;
    dividendDesc?: string | null;
  } | null;
  pdfUrl?: string | null;
  publishedAt?: string | null;
}

/** 类型徽标文案：annTypes 中文（剔 OTHER）→ annTypeSource → 「其他」（与推送卡片 typeLabel 同链）。 */
function announcementTypeLabel(it: AnnouncementItem): string {
  const joined = (it.annTypes ?? [])
    .filter((t) => t !== "OTHER")
    .map((t) => ANN_TYPE_LABEL[t] ?? t)
    .join("、");
  if (joined) return joined;
  return it.annTypeSource && it.annTypeSource.trim() !== "" ? it.annTypeSource : ANN_TYPE_LABEL.OTHER;
}

/** 六字段要点行（metrics 非空字段才出；同比/毛利率带符号；全空返回空串不渲染）。 */
function announcementMetricsLine(it: AnnouncementItem): string {
  const m = it.metrics;
  if (!m) return "";
  const pct = (v: number) => (v >= 0 ? "+" : "") + v + "%";
  const parts: string[] = [];
  if (m.revenueYi != null) parts.push(`营收 ${m.revenueYi} 亿`);
  if (m.netProfitYi != null) parts.push(`归母净利 ${m.netProfitYi} 亿`);
  if (m.netProfitYoyPct != null) parts.push(`净利同比 ${pct(m.netProfitYoyPct)}`);
  if (m.deductedProfitYi != null) parts.push(`扣非 ${m.deductedProfitYi} 亿`);
  if (m.grossMarginPct != null) parts.push(`毛利率 ${pct(m.grossMarginPct)}`);
  if (m.dividendDesc) parts.push(`分红 ${m.dividendDesc}`);
  return parts.join(" · ");
}

function AnnouncementListCard({ status, result, scope }: {
  status: "inProgress" | "executing" | "complete";
  result?: string;
  scope?: string;
}) {
  const parsed = useMemo(() => {
    if (status !== "complete" || typeof result !== "string") return null;
    let json: unknown;
    try {
      json = JSON.parse(result);
    } catch {
      return { degrade: true as const };
    }
    if (json == null || typeof json !== "object" || Array.isArray(json)) return { degrade: true as const };
    const body = json as { items?: unknown; message?: unknown; total?: unknown; error?: unknown };
    if (body.error != null) return { degrade: true as const };
    if (!Array.isArray(body.items)) return { degrade: true as const };
    return {
      items: body.items as AnnouncementItem[],
      message: typeof body.message === "string" ? body.message : null,
      total: typeof body.total === "number" ? body.total : null,
    };
  }, [status, result]);

  if (parsed == null)
    return (
      <div className="tool-card running my-2 w-full max-w-[560px] px-3 py-2 text-xs text-[color:var(--color-ink-faint)]">
        search_announcements 执行中…
      </div>
    );
  if (parsed.degrade)
    return (
      <details className="tool-card my-2 w-full max-w-[560px] px-3 py-2 text-xs">
        <summary className="cursor-pointer text-[color:var(--color-ink-dim)]">数据异常（原始结果折叠）</summary>
        <pre className="mt-2 max-h-[320px] overflow-auto whitespace-pre-wrap break-all text-[color:var(--color-ink-faint)]">
          {result!.slice(0, 2000)}
        </pre>
      </details>
    );
  const scopeLabel = scope ? SCOPE_LABEL[scope] : undefined; // all/缺省不回显
  return (
    <div className="tool-card my-2 w-full max-w-[560px] px-3.5 py-2.5" data-testid="announcement-list-card">
      <div className="mb-1 flex items-center justify-between text-xs">
        <span className="flex items-center gap-1.5">
          <span className="font-medium text-[color:var(--color-ink-dim)]">公告检索</span>
          {scopeLabel && (
            <span className="rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[10px] leading-none text-[color:var(--color-ink-dim)]">
              {scopeLabel}
            </span>
          )}
        </span>
        {parsed.total != null && (
          <span className="tabular text-[color:var(--color-ink-faint)]">共 {parsed.total} 条</span>
        )}
      </div>
      {parsed.message ? (
        <div className="py-1 text-xs text-[color:var(--color-ink-faint)]">{parsed.message}</div>
      ) : (
        <ul className="divide-y divide-[color:var(--color-line-soft)]">
          {parsed.items.map((it, i) => {
            const time = formatPublished(it.publishedAt);
            const metricsLine = announcementMetricsLine(it);
            const stockText = [it.stockName, it.stockCode].filter(Boolean).join(" ");
            return (
              <li key={it.pdfUrl ?? it.title ?? i} className="py-2">
                <div className="flex items-start gap-2">
                  {it.pdfUrl ? (
                    <a
                      href={it.pdfUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="min-w-0 flex-1 truncate text-[13px] text-[color:var(--color-accent)] hover:underline"
                    >
                      {it.title ?? "(无标题)"}
                    </a>
                  ) : (
                    <span className="min-w-0 flex-1 truncate text-[13px] text-[color:var(--color-ink)]">
                      {it.title ?? "(无标题)"}
                    </span>
                  )}
                  <span className="shrink-0 rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[10px] leading-none text-[color:var(--color-ink-dim)]">
                    {announcementTypeLabel(it)}
                  </span>
                  {time && (
                    <span className="tabular shrink-0 text-[11px] text-[color:var(--color-ink-faint)]">
                      {time}
                    </span>
                  )}
                </div>
                {(stockText || metricsLine) && (
                  <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-[11px] text-[color:var(--color-ink-faint)]">
                    {stockText && <span>{stockText}</span>}
                    {metricsLine && <span>{metricsLine}</span>}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

export function ChartToolRenderers() {
  useRenderTool({
    name: "get_kline",
    parameters: KlineParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} builder={buildCandlestickOption as ChartCardBuilder} />,
  });
  useRenderTool({
    name: "get_valuation",
    parameters: ValuationParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} builder={buildLineOption as ChartCardBuilder} />,
  });
  useRenderTool({
    name: "get_market_overview",
    parameters: OverviewParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} builder={buildBarOption as ChartCardBuilder} />,
  });
  useRenderTool({
    name: "get_financials",
    parameters: FinancialsParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  // ===== MS-12：4 个 table 工具不传 builder（ChartCard 内接 DataTable），pie 传 buildPieOption =====
  useRenderTool({
    name: "screen_stocks",
    parameters: ScreeningParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  useRenderTool({
    name: "analyze_financials",
    parameters: FinancialsTrendParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  useRenderTool({
    name: "analyze_industry",
    parameters: IndustryParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  useRenderTool({
    name: "suggest_allocation",
    parameters: AllocationParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} />,
  });
  useRenderTool({
    name: "analyze_portfolio",
    parameters: PortfolioParamsSchema,
    render: (p) => <ChartCard status={p.status} result={p.result} name={p.name} builder={buildPieOption as ChartCardBuilder} />,
  });
  // ===== invest-sop P1（Ruling-1）：research_draft 草稿卡——按工具名注册（工具名在 tool call
  // 块确定可得，不扫描助手消息），渲染器从工具结果文本提取 ```research-draft 围栏内 JSON 交
  // DraftCard；无围栏（参数错误文本）原样透传 → DraftCard 降级卡。参数 schema 为具名重载
  // 必填的类型载体（运行时不校验，ToolCallRenderer 直接透传 partialJSONParse 产物），渲染
  // 不消费 parameters，就地定义而不入 tool-params.ts（本任务限定只动四文件）。 =====
  useRenderTool({
    name: "research_draft",
    parameters: z.object({
      stage: z.string(),
      draftJson: z.string(),
    }),
    render: (p) => {
      if (p.status !== "complete" || typeof p.result !== "string") {
        return (
          <div className="tool-card running my-2 w-full max-w-[560px] px-3 py-2 text-xs text-[color:var(--color-ink-faint)]">
            {p.name} 执行中…
          </div>
        );
      }
      return <DraftCard raw={extractResearchDraftJson(p.result) ?? p.result} onSave={saveResearchDraft} />;
    },
  });
  // ===== MS-20（Task 12）：search_news 新闻检索——列表卡（非图表通道），参数 schema 引
  // tool-params，卡片内部按 items/message 契约渲染（空结果渲染 message 行）。 =====
  useRenderTool({
    name: "search_news",
    parameters: SearchNewsParamsSchema,
    render: (p) => <NewsListCard status={p.status} result={p.result} />,
  });
  // ===== MS-21（P2 Task 8）：search_announcements 公告检索——列表卡（标题链接 pdf_url/
  // 类型徽标/六字段要点行/日期），scope 参数回显（all/缺省不回显）。 =====
  useRenderTool({
    name: "search_announcements",
    parameters: SearchAnnouncementsParamsSchema,
    render: (p) => (
      <AnnouncementListCard status={p.status} result={p.result} scope={p.parameters?.scope} />
    ),
  });
  return null;
}
