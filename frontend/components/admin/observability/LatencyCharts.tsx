"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildBarOption, buildLineOption } from "@/components/charts/optionBuilders";
import { adminApi, type LatencyAgg } from "@/lib/adminApi";

const WINDOW_OPTIONS = [7, 30, 90] as const;

const statChip =
  "rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-2.5";

/** 观测区块③：时延看板（近 N 天）——轮整体 p50/p95 + 按日折线（null 缺口留白）+ 按工具条形。 */
export default function LatencyCharts() {
  const [days, setDays] = useState<(typeof WINDOW_OPTIONS)[number]>(7);
  const [agg, setAgg] = useState<LatencyAgg | null>(null);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0); // 竞态守卫（沿 AnalyticsBoard.tsx:52-58）

  const load = useCallback((targetDays: number) => {
    const seq = ++requestSeqRef.current;
    adminApi
      .fetchLatency(targetDays)
      .then((a) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的窗口请求，丢弃过期响应
        setAgg(a);
        setError(null);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载时延看板失败");
      });
  }, []);

  useEffect(() => {
    load(days);
  }, [load, days]);

  const empty =
    agg != null &&
    agg.turn.p50Ms == null &&
    agg.turn.p95Ms == null &&
    agg.turn.byDay.length === 0 &&
    agg.tool.byTool.length === 0;

  const lineOption = useMemo(
    () =>
      agg && agg.turn.byDay.length > 0
        ? buildLineOption({
            specVersion: 1,
            type: "line",
            title: "按日轮时延",
            categories: agg.turn.byDay.map((d) => d.date),
            series: [
              { name: "p50", data: agg.turn.byDay.map((d) => d.p50Ms) },
              { name: "p95", data: agg.turn.byDay.map((d) => d.p95Ms) },
            ],
          })
        : null,
    [agg],
  );

  const barOption = useMemo(
    () =>
      agg && agg.tool.byTool.length > 0
        ? buildBarOption({
            specVersion: 1,
            type: "bar",
            title: "按工具时延",
            categories: agg.tool.byTool.map((t) => t.tool),
            series: [
              { name: "p50", data: agg.tool.byTool.map((t) => t.p50Ms) },
              { name: "p95", data: agg.tool.byTool.map((t) => t.p95Ms) },
            ],
          })
        : null,
    [agg],
  );

  return (
    <section aria-label="时延看板" className="mt-8">
      <div className="flex flex-wrap items-center gap-3">
        <h3 className="text-[14px] font-medium text-[color:var(--color-ink-dim)]">时延看板</h3>
        <div className="flex gap-1">
          {WINDOW_OPTIONS.map((d) => (
            <button
              key={d}
              type="button"
              onClick={() => setDays(d)}
              className={
                d === days
                  ? "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-line-soft)] px-2.5 py-1 text-[11px] text-[color:var(--color-ink)]"
                  : "rounded-md border border-transparent px-2.5 py-1 text-[11px] text-[color:var(--color-ink-faint)] transition-all hover:text-[color:var(--color-ink-dim)]"
              }
            >
              近 {d} 天
            </button>
          ))}
        </div>
      </div>

      {error && (
        <p role="alert" className="mt-3 text-[13px] text-[color:var(--color-up)]">
          {error}
        </p>
      )}

      {empty ? (
        <p
          data-testid="latency-empty"
          className="mt-3 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-6 text-center text-[13px] text-[color:var(--color-ink-faint)]"
        >
          暂无轮时延观测：对话产生后自动采集
        </p>
      ) : (
        agg && (
          <>
            <div className="mt-3 flex gap-3">
              <div className={statChip}>
                <p className="text-[11px] text-[color:var(--color-ink-faint)]">轮时延 p50</p>
                <p className="mt-0.5 font-[family-name:var(--font-mono)] text-[15px] text-[color:var(--color-ink)]">
                  {agg.turn.p50Ms != null ? `${Math.round(agg.turn.p50Ms)} ms` : "—"}
                </p>
              </div>
              <div className={statChip}>
                <p className="text-[11px] text-[color:var(--color-ink-faint)]">轮时延 p95</p>
                <p className="mt-0.5 font-[family-name:var(--font-mono)] text-[15px] text-[color:var(--color-ink)]">
                  {agg.turn.p95Ms != null ? `${Math.round(agg.turn.p95Ms)} ms` : "—"}
                </p>
              </div>
            </div>
            <div className="mt-4 grid gap-4 lg:grid-cols-2">
              {lineOption && <EChart option={lineOption} height={240} testid="latency-turn-line" />}
              {barOption && <EChart option={barOption} height={240} testid="latency-tool-bar" />}
            </div>
          </>
        )
      )}
    </section>
  );
}
