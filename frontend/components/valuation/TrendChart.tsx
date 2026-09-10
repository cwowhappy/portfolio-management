"use client";

import { useMemo } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildLineOption } from "@/components/charts/optionBuilders";
import type { ValuationSnapshot, IndexValuationSeries } from "@/lib/types";

interface TrendPoint {
  day: string;
  pe: number | null;
  pb: number | null;
}

function toPoints(
  snapshots: ValuationSnapshot[],
  indexValuations: IndexValuationSeries[],
  selectedIndex: string,
): TrendPoint[] {
  if (selectedIndex === "market") {
    return snapshots.map((s) => ({ day: s.tradingDay, pe: s.peMedian, pb: s.pbMedian }));
  }
  return indexValuations
    .filter((p) => p.indexCode === selectedIndex)
    .slice()
    .sort((a, b) => a.tradingDay.localeCompare(b.tradingDay))
    .map((p) => ({ day: p.tradingDay, pe: p.pe, pb: p.pb }));
}

export default function TrendChart({
  snapshots,
  indexValuations = [],
  selectedIndex = "market",
}: {
  snapshots: ValuationSnapshot[];
  indexValuations?: IndexValuationSeries[];
  selectedIndex?: string;
}) {
  const data = toPoints(snapshots, indexValuations, selectedIndex);
  const option = useMemo(
    () =>
      buildLineOption({
        specVersion: 1,
        type: "line",
        title: "估值历史走势",
        categories: data.map((p) => p.day),
        series: [
          { name: "PE", data: data.map((p) => p.pe) },
          { name: "PB", data: data.map((p) => p.pb) },
        ],
      }),
    [data],
  );
  if (data.length === 0) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">估值历史走势</div>
        <div className="text-sm text-[color:var(--color-ink-faint)]">数据积累中</div>
      </div>
    );
  }
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">估值历史走势</div>
      <EChart option={option} height={240} testid="trend-chart" />
    </div>
  );
}
