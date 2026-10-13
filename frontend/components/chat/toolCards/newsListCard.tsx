"use client";
// ===== MS-20（Task 12）：search_news 新闻检索列表卡 =====
// 后端契约：{"items":[{title,summary,direction,importance,keyNumbers,stockCodes,url,publishedAt}],"total":n}，
// 空结果 {"items":[],"message":"该条件下暂无情报（新闻仅保留 90 天内）"}，错误 {"error","hint"}。
// 方向徽标取情报语义配色（BULLISH=利好→绿 / BEARISH=利空→红 / NEUTRAL=中性→灰），与 A股
// 红涨绿跌的行情色相反：绿/红仅复用 --color-down / --color-up 的色值以随深浅主题联动。
// （2026-10 P3 审查 F4 自 toolRenderers.tsx 按卡拆目录，纯搬移零行为变更。）

import { useMemo } from "react";
import { safeUrl } from "@/lib/url";
import { formatPublished } from "./publishedAt";

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

export function NewsListCard({ status, result }: {
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
            const href = safeUrl(it.url);
            return (
              <li key={it.url ?? it.title ?? i} className="py-2">
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
