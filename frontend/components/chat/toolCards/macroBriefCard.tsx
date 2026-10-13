"use client";
// ===== MS-22（P3 Task 6）：macro_brief 宏观简报卡——指标表 + 政策列表两段 =====
// 后端契约：{"indicators":[{indicator,value,yoy?,period,periodType,series:[{period,value}]|note}],
// "policies":[{title,direction,strength,areas,summary,confidence,isPolicy,url,publishedAt}],
// "total":n,"missing":[{indicator,missing:true}],"generatedAt":iso}，
// 空政策 {"policies":[],"note":"..."}，错误 {"error","hint"}。TY 两点 series 空由后端翻译为
// note 字段（T4 裁定——跨表只合成最新点，勿当数据缺失）；缺失指标显式「X：数据缺失」行
// （F13 不编造不省略）；政策方向徽标取政策语义配色（EASING=宽松→蓝 / TIGHTENING=收紧→红 /
// NEUTRAL=中性→灰），isPolicy=false 兜底行带「非政策类」灰标。
// （2026-10 P3 审查 F4 自 toolRenderers.tsx 按卡拆目录，纯搬移零行为变更。）

import { useMemo } from "react";
import { safeUrl } from "@/lib/url";
import { formatPublished } from "./publishedAt";

const POLICY_DIRECTION_LABEL: Record<string, string> = {
  EASING: "宽松",
  TIGHTENING: "收紧",
  NEUTRAL: "中性",
};
const POLICY_DIRECTION_CLASS: Record<string, string> = {
  EASING: "border-[color:var(--color-accent)] text-[color:var(--color-accent)]",
  TIGHTENING: "border-[color:var(--color-up)] text-[color:var(--color-up)]",
  NEUTRAL: "border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]",
};
const POLICY_STRENGTH_LABEL: Record<string, string> = {
  HIGH: "强",
  MEDIUM: "中",
  LOW: "弱",
};

interface MacroIndicatorEntry {
  indicator?: string | null;
  value?: number | null;
  period?: string | null;
  series?: { period?: string | null; value?: number | null }[] | null;
  note?: string | null;
}

interface MacroPolicyEntry {
  title?: string | null;
  direction?: string | null;
  strength?: string | null;
  areas?: string[] | null;
  summary?: string | null;
  isPolicy?: boolean | null;
  url?: string | null;
  publishedAt?: string | null;
}

/** 近 5 期迷你串：最新在前 → 值以 → 连接（缺值期跳过；全空返回空串）。 */
function macroSeriesText(entry: MacroIndicatorEntry): string {
  return (entry.series ?? [])
    .map((p) => (p.value == null ? "" : String(p.value)))
    .filter((s) => s !== "")
    .join("→");
}

export function MacroBriefCard({ status, result }: {
  status: "inProgress" | "executing" | "complete";
  result?: string;
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
    const body = json as {
      indicators?: unknown;
      policies?: unknown;
      missing?: unknown;
      note?: unknown;
      total?: unknown;
      generatedAt?: unknown;
      error?: unknown;
    };
    if (body.error != null) return { degrade: true as const };
    if (!Array.isArray(body.indicators) || !Array.isArray(body.policies)) return { degrade: true as const };
    return {
      indicators: body.indicators as MacroIndicatorEntry[],
      policies: body.policies as MacroPolicyEntry[],
      missing: Array.isArray(body.missing) ? (body.missing as { indicator?: string }[]) : [],
      note: typeof body.note === "string" ? body.note : null,
      total: typeof body.total === "number" ? body.total : null,
      generatedAt: typeof body.generatedAt === "string" ? body.generatedAt : null,
    };
  }, [status, result]);

  if (parsed == null)
    return (
      <div className="tool-card running my-2 w-full max-w-[560px] px-3 py-2 text-xs text-[color:var(--color-ink-faint)]">
        macro_brief 执行中…
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
  const generatedAt = formatPublished(parsed.generatedAt);
  return (
    <div className="tool-card my-2 w-full max-w-[560px] px-3.5 py-2.5" data-testid="macro-brief-card">
      <div className="mb-1 flex items-center justify-between text-xs">
        <span className="font-medium text-[color:var(--color-ink-dim)]">宏观简报</span>
        {parsed.total != null && (
          <span className="tabular text-[color:var(--color-ink-faint)]">共 {parsed.total} 条</span>
        )}
      </div>
      <table className="w-full text-xs">
        <thead>
          <tr className="text-left text-[color:var(--color-ink-faint)]">
            <th className="py-1 pr-2 font-normal">指标</th>
            <th className="py-1 pr-2 font-normal">最新值</th>
            <th className="py-1 pr-2 font-normal">期别</th>
            <th className="py-1 font-normal">近5期</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-[color:var(--color-line-soft)]">
          {parsed.indicators.map((it, i) => {
            const mini = macroSeriesText(it);
            return (
              <tr key={it.indicator ?? i}>
                <td className="py-1.5 pr-2 text-[color:var(--color-ink)]">{it.indicator ?? "—"}</td>
                <td className="tabular py-1.5 pr-2 text-[color:var(--color-ink)]">
                  {it.value == null ? "—" : String(it.value)}
                </td>
                <td className="tabular py-1.5 pr-2 text-[color:var(--color-ink-faint)]">{it.period ?? "—"}</td>
                <td className="tabular py-1.5 text-[color:var(--color-ink-dim)]">
                  {mini || it.note || "—"}
                </td>
              </tr>
            );
          })}
          {parsed.missing.map((m, i) => (
            <tr key={`missing-${m.indicator ?? i}`} className="text-[color:var(--color-ink-faint)]">
              <td className="py-1.5 pr-2">{m.indicator ?? "—"}</td>
              <td className="py-1.5" colSpan={3}>
                数据缺失
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {parsed.note ? (
        <div className="mt-1.5 text-xs text-[color:var(--color-ink-faint)]">{parsed.note}</div>
      ) : (
        <ul className="mt-1.5 divide-y divide-[color:var(--color-line-soft)] border-t border-[color:var(--color-line-soft)]">
          {parsed.policies.map((it, i) => {
            const dirLabel = it.direction ? POLICY_DIRECTION_LABEL[it.direction] : undefined;
            const strength = it.strength ? POLICY_STRENGTH_LABEL[it.strength] : undefined;
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
                  {it.isPolicy === false && (
                    <span className="shrink-0 rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[10px] leading-none text-[color:var(--color-ink-faint)]">
                      非政策类
                    </span>
                  )}
                  {dirLabel && (
                    <span
                      className={
                        "shrink-0 rounded border px-1.5 py-0.5 text-[10px] leading-none " +
                        (POLICY_DIRECTION_CLASS[it.direction!] ?? POLICY_DIRECTION_CLASS.NEUTRAL)
                      }
                    >
                      {dirLabel}
                    </span>
                  )}
                  {strength && (
                    <span className="shrink-0 text-[11px] text-[color:var(--color-ink-faint)]">{strength}</span>
                  )}
                  {time && (
                    <span className="tabular shrink-0 text-[11px] text-[color:var(--color-ink-faint)]">{time}</span>
                  )}
                </div>
                {it.summary && (
                  <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-[color:var(--color-ink-dim)]">
                    {it.summary}
                  </p>
                )}
                {(it.areas?.length ?? 0) > 0 && (
                  <div className="mt-1 text-[11px] text-[color:var(--color-ink-faint)]">
                    {it.areas!.join(" / ")}
                  </div>
                )}
              </li>
            );
          })}
        </ul>
      )}
      {generatedAt && (
        <div className="mt-1 text-[11px] text-[color:var(--color-ink-faint)]">生成于 {generatedAt}</div>
      )}
    </div>
  );
}
