"use client";
// 政策库 Panel：顶部宏观日历卡（fetchMacroCalendar(7) 未来 7 天发布日程 + 最近维护时间）
// + 方向过滤 + 列表 + 服务端分页。v1 日历卡不引图表（计划 YAGNI 裁定）。
// 方向徽标色照 chat 工具卡先例：EASING=宽松蓝(accent) / TIGHTENING=收紧红(up) / NEUTRAL=灰；
// isPolicy=false 为非政策兜底行，带灰标「非政策类」降权标注。

import { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchMacroCalendar,
  fetchPolicies,
  type MacroCalendar,
  type PolicyDirection,
  type PolicyPage,
} from "@/lib/intelligenceApi";
import {
  IntelEmptyFiltered,
  IntelEmptyGuide,
  LabeledSelect,
  formatPublished,
} from "./IntelligenceFilters";
import PaginationBar from "./PaginationBar";

// 枚举→中文（T3 审查裁定：组件层 Record<枚举,string>，编译期漏键即错）
const DIRECTION_LABELS: Record<PolicyDirection, string> = {
  EASING: "宽松",
  TIGHTENING: "收紧",
  NEUTRAL: "中性",
};
const DIRECTION_CLASS: Record<PolicyDirection, string> = {
  EASING: "border-[color:var(--color-accent)] text-[color:var(--color-accent)]",
  TIGHTENING: "border-[color:var(--color-up)] text-[color:var(--color-up)]",
  NEUTRAL: "border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]",
};
const STRENGTH_LABELS: Record<string, string> = { HIGH: "强", MEDIUM: "中", LOW: "弱" };
const DIRECTION_OPTIONS: Array<{ value: PolicyDirection | ""; label: string }> = [
  { value: "", label: "全部方向" },
  ...(Object.keys(DIRECTION_LABELS) as PolicyDirection[]).map((d) => ({
    value: d,
    label: DIRECTION_LABELS[d],
  })),
];

const PAGE_SIZE = 20;

/** 日历卡最近维护时间：全条目 updatedAt 最大值（ISO 字典序即时间序）。 */
function maintainedAt(calendar: MacroCalendar): string | null {
  return calendar.reduce<string | null>(
    (max, c) => (c.updatedAt && (!max || c.updatedAt > max) ? c.updatedAt : max),
    null,
  );
}

export default function PolicyPanel({ q }: { q: string }) {
  const [direction, setDirection] = useState<PolicyDirection | "">("");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<PolicyPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [calendar, setCalendar] = useState<MacroCalendar | null>(null);
  const requestSeqRef = useRef(0);

  // 日历卡独立拉取：无分页无过滤，失败静默为空（不阻塞政策列表）
  useEffect(() => {
    let cancelled = false;
    fetchMacroCalendar(7)
      .then((c) => {
        if (!cancelled) setCalendar(c);
      })
      .catch(() => {
        if (!cancelled) setCalendar([]);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    fetchPolicies({
      q: q || undefined,
      direction: direction || undefined,
      page,
      pageSize: PAGE_SIZE,
    })
      .then((d) => {
        if (seq !== requestSeqRef.current) return;
        setData(d);
        setError(null);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载失败");
      })
      .finally(() => {
        if (seq === requestSeqRef.current) setLoading(false);
      });
  }, [q, direction, page]);

  useEffect(() => {
    reload();
  }, [reload]);

  if (error) {
    return (
      <div
        className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-6 text-sm text-[color:var(--color-ink-dim)] space-y-3"
        data-testid="intel-error"
      >
        <div>加载失败：{error}</div>
        <button
          type="button"
          className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-xs text-[color:var(--color-ink-dim)] hover:border-[color:var(--color-ink-faint)]"
          onClick={() => {
            setError(null);
            reload();
          }}
        >
          重试
        </button>
      </div>
    );
  }

  const maintained = calendar ? maintainedAt(calendar) : null;
  const hasFilter = !!(q || direction);

  return (
    <div className="space-y-4" data-testid="intel-policies-panel">
      <div
        className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-4"
        data-testid="macro-calendar-card"
      >
        <div className="flex flex-wrap items-center justify-between gap-2 text-xs">
          <span className="font-medium text-[color:var(--color-ink-dim)]">未来 7 天数据日历</span>
          {maintained && (
            <span className="tabular text-[color:var(--color-ink-faint)]">
              维护至 {formatPublished(maintained)}
            </span>
          )}
        </div>
        {calendar == null || calendar.length === 0 ? (
          <div className="mt-2 text-xs text-[color:var(--color-ink-faint)]">
            未来 7 天暂无数据发布日程
          </div>
        ) : (
          <ul className="mt-2 flex flex-wrap gap-x-5 gap-y-1 text-xs">
            {calendar.map((c, i) => (
              <li key={`${c.indicator}-${c.expectedDate}-${i}`} className="text-[color:var(--color-ink-dim)]">
                <span className="text-[color:var(--color-ink)]">{c.indicator}</span>
                <span className="ml-1.5 tabular text-[color:var(--color-ink-faint)]">
                  {c.expectedDate}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <LabeledSelect
          label="政策方向"
          value={direction}
          options={DIRECTION_OPTIONS}
          onChange={(v) => {
            setDirection(v);
            setPage(1);
          }}
        />
      </div>

      {loading ? (
        <div className="h-40 rounded-2xl skeleton" aria-label="加载中" />
      ) : data == null ? null : data.items.length === 0 ? (
        hasFilter ? (
          <IntelEmptyFiltered
            onClear={() => {
              setDirection("");
              setPage(1);
            }}
          />
        ) : (
          <IntelEmptyGuide />
        )
      ) : (
        <>
          <ul
            className="divide-y divide-[color:var(--color-line-soft)] rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 px-4"
            data-testid="intel-policies-list"
          >
            {data.items.map((it) => {
              const time = formatPublished(it.publishedAt);
              return (
                <li key={it.url} className="py-3">
                  <div className="flex items-start gap-2">
                    <a
                      href={it.url}
                      target="_blank"
                      rel="noreferrer"
                      className="min-w-0 flex-1 truncate text-sm text-[color:var(--color-accent)] hover:underline"
                    >
                      {it.title}
                    </a>
                    {it.isPolicy === false && (
                      <span className="shrink-0 rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[10px] leading-none text-[color:var(--color-ink-faint)]">
                        非政策类
                      </span>
                    )}
                    {it.direction && (
                      <span
                        className={
                          "shrink-0 rounded border px-1.5 py-0.5 text-[10px] leading-none " +
                          DIRECTION_CLASS[it.direction]
                        }
                      >
                        {DIRECTION_LABELS[it.direction]}
                      </span>
                    )}
                    {it.strength && (
                      <span className="shrink-0 text-[11px] text-[color:var(--color-ink-faint)]">
                        {STRENGTH_LABELS[it.strength] ?? it.strength}
                      </span>
                    )}
                    {time && (
                      <span className="shrink-0 text-[11px] tabular text-[color:var(--color-ink-faint)]">
                        {time}
                      </span>
                    )}
                  </div>
                  {it.summary && (
                    <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-[color:var(--color-ink-dim)]">
                      {it.summary}
                    </p>
                  )}
                  {it.areas.length > 0 && (
                    <div className="mt-1 flex flex-wrap gap-1.5">
                      {it.areas.map((a) => (
                        <span
                          key={a}
                          className="rounded border border-[color:var(--color-line-soft)] px-1.5 py-0.5 text-[10px] text-[color:var(--color-ink-faint)]"
                        >
                          {a}
                        </span>
                      ))}
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
          <PaginationBar
            page={data.page}
            pageSize={data.pageSize}
            total={data.total}
            onPageChange={setPage}
          />
        </>
      )}
    </div>
  );
}
