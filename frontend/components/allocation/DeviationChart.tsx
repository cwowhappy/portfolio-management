"use client";

import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Legend, CartesianGrid } from "recharts";
import type { DeviationView } from "@/lib/types";
import { ASSET_CLASS_LABELS } from "@/lib/allocationApi";

export default function DeviationChart({ deviation }: { deviation: DeviationView | null }) {
  const slices = deviation?.slices ?? [];
  if (slices.length === 0) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">目标 vs 实际配置</div>
        <div className="text-sm text-[color:var(--color-ink-faint)]">暂无生效方案，先套用模板或创建方案并设为生效</div>
      </div>
    );
  }
  const data = slices.map((s) => ({
    name: ASSET_CLASS_LABELS[s.assetClass],
    目标: s.targetWeight,
    实际: s.actualWeight,
  }));
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="deviation-chart">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">目标 vs 实际配置</div>
      <ResponsiveContainer width="100%" height={220}>
        <BarChart data={data}>
          <CartesianGrid strokeDasharray="3 3" stroke="var(--color-line)" />
          <XAxis dataKey="name" />
          <YAxis unit="%" />
          <Tooltip contentStyle={{ background: "var(--color-panel)", border: "1px solid var(--color-line)" }} />
          <Legend />
          <Bar dataKey="目标" fill="var(--color-ink-faint)" />
          <Bar dataKey="实际" fill="var(--color-up)" />
        </BarChart>
      </ResponsiveContainer>
      <div className="mt-3 text-xs text-[color:var(--color-ink-faint)]">
        {slices.map((s) => (
          <span key={s.assetClass} className="mr-4">
            {ASSET_CLASS_LABELS[s.assetClass]} 偏离 {s.deviation > 0 ? "+" : ""}{s.deviation}%
          </span>
        ))}
      </div>
    </div>
  );
}
