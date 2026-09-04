"use client";

import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
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
      <ResponsiveContainer width="100%" height={240}>
        <LineChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
          <CartesianGrid stroke="var(--color-line-soft)" strokeDasharray="3 3" />
          <XAxis dataKey="day" stroke="var(--color-ink-faint)" fontSize={12} />
          <YAxis stroke="var(--color-ink-faint)" fontSize={12} />
          <Tooltip contentStyle={{ background: "var(--color-panel)", border: "1px solid var(--color-line)" }} />
          <Line type="monotone" dataKey="pe" name="PE" stroke="var(--color-up)" dot={false} />
          <Line type="monotone" dataKey="pb" name="PB" stroke="var(--color-accent)" dot={false} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
