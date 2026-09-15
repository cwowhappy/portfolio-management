"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { fetchOverview, fetchNav, fetchAnnual, fetchTradeStats } from "@/lib/analyticsApi";
import type { AnalyticsNav, AnalyticsOverview, AnnualReturnRow, TradeStatsView } from "@/lib/types";
import OverviewCards from "@/components/analytics/OverviewCards";
import NavChart, { type NormalizedNav } from "@/components/analytics/NavChart";
import AnnualTable from "@/components/analytics/AnnualTable";
import TradeStatsCards from "@/components/analytics/TradeStatsCards";

/** 归一化：序列各值除以自身窗口首值 ×1000（首日恒为 1000，消绝对规模便于跨序列对比）。 */
function normalize(values: number[]): number[] {
  return values.map((v) => (v / values[0]) * 1000);
}

/**
 * 组合 + 基准按日期交集对齐：基准窗口不同时先截齐到共同日期再各自归一。
 * 与组合窗口完全无重叠的基准剔除（防一条坏基准清空整图），此时组合按自身全窗口归一。
 */
export function toNormalizedNav(nav: AnalyticsNav, names: Record<string, string>): NormalizedNav {
  const portfolioByDate = new Map(nav.points.map((p) => [p.date, p.totalValue]));
  const benchmarks = Object.entries(nav.benchmarks).map(([code, pts]) => ({
    code,
    name: names[code] ?? code,
    byDate: new Map(pts.map((p) => [p.date, p.close])),
  }));
  let dates = nav.points.map((p) => p.date);
  const usable = benchmarks.filter((b) => dates.some((d) => b.byDate.has(d)));
  for (const b of usable) dates = dates.filter((d) => b.byDate.has(d));
  return {
    dates,
    portfolio: normalize(dates.map((d) => portfolioByDate.get(d)!)),
    benchmarks: usable.map((b) => ({
      code: b.code,
      name: b.name,
      values: normalize(dates.map((d) => b.byDate.get(d)!)),
    })),
  };
}

export default function AnalyticsBoard() {
  const [loading, setLoading] = useState(true);
  const [overview, setOverview] = useState<AnalyticsOverview | null>(null);
  const [nav, setNav] = useState<AnalyticsNav | null>(null);
  const [annual, setAnnual] = useState<AnnualReturnRow[]>([]);
  const [tradeStats, setTradeStats] = useState<TradeStatsView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    Promise.all([fetchOverview(), fetchNav(), fetchAnnual(), fetchTradeStats()])
      .then(([o, n, a, t]) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的 reload，丢弃过期响应
        setOverview(o ?? null); // overview/nav/trade-stats 204 → undefined，归一为 null 走块级空态
        setNav(n ?? null);
        setAnnual(a);
        setTradeStats(t ?? null);
        setLoading(false);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载失败");
      });
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  // 基准展示名：overview 携带 indexName；overview 空而 nav 有数据的边角回退用代码本身
  const names = useMemo(() => {
    const m: Record<string, string> = {};
    for (const [code, b] of Object.entries(overview?.benchmarks ?? {})) m[code] = b.indexName;
    return m;
  }, [overview]);
  const navData = useMemo(
    () => (nav != null && nav.points.length > 0 ? toNormalizedNav(nav, names) : null),
    [nav, names],
  );

  if (error) return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;
  if (loading) return <div className="p-8 skeleton h-40 rounded-2xl" />;
  if (!overview) {
    return (
      <div data-testid="analytics-empty" className="mx-auto max-w-6xl px-6 py-8">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">收益分析</h1>
        <div className="mt-6 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-8 text-[color:var(--color-ink-dim)]">
          暂无数据：录入持仓流水后此处展示收益分析
        </div>
      </div>
    );
  }
  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <h1 className="font-[family-name:var(--font-display)] text-2xl">收益分析</h1>
      <OverviewCards overview={overview} />
      {navData != null ? (
        <NavChart data={navData} />
      ) : (
        <div data-testid="nav-chart" className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-8 text-[color:var(--color-ink-dim)]">
          暂无数据
        </div>
      )}
      <AnnualTable rows={annual} names={names} />
      {tradeStats != null ? (
        <TradeStatsCards stats={tradeStats} />
      ) : (
        <div data-testid="trade-stats" className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-8 text-[color:var(--color-ink-dim)]">
          暂无数据
        </div>
      )}
    </div>
  );
}
