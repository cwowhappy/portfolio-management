"use client";
// ===== MS-21（P2 Task 8）：search_announcements 公告检索列表卡 =====
// 后端契约：{"items":[{title,stockCode,stockName,annTypes[],annTypeSource,metrics,pdfUrl,publishedAt}],"total":n}，
// 空结果 {"items":[],"message":"..."}（scope 空集引导语 / 该标的无公告 / 检索无结果），
// 错误 {"error","hint"}。类型徽标链与后端推送卡片同款：annTypes 中文（剔 OTHER）→ annTypeSource → 「其他」。
// （2026-10 P3 审查 F4 自 toolRenderers.tsx 按卡拆目录，纯搬移零行为变更。）

import { useMemo } from "react";
import { safeUrl } from "@/lib/url";
import { formatPublished } from "./publishedAt";

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

export function AnnouncementListCard({ status, result, scope }: {
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
            const href = safeUrl(it.pdfUrl);
            return (
              <li key={it.pdfUrl ?? it.title ?? i} className="py-2">
                <div className="flex items-start gap-2">
                  {href ? (
                    <a
                      href={href}
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
