"use client";

import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, Cell } from "recharts";
import type { IndustryDistribution } from "@/lib/types";

export default function IndustryBar({ industry }: { industry: IndustryDistribution | null }) {
  const data = industry?.slices ?? [];
  if (data.length === 0) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">行业分布</div>
        <div className="text-sm text-[color:var(--color-ink-faint)]">暂无数据（个股需有申万行业映射）</div>
      </div>
    );
  }
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">行业分布</div>
      <ResponsiveContainer width="100%" height={200}>
        <BarChart data={data} margin={{ top: 4, right: 8, left: 0, bottom: 0 }}>
          <XAxis dataKey="industryName" stroke="var(--color-ink-faint)" fontSize={12} />
          <YAxis stroke="var(--color-ink-faint)" fontSize={12} />
          <Tooltip contentStyle={{ background: "var(--color-panel)", border: "1px solid var(--color-line)" }} />
          <Bar dataKey="marketValue" name="市值" fill="var(--color-up)" radius={[4, 4, 0, 0]}>
            {data.map((_, i) => <Cell key={i} fill="var(--color-up)" />)}
          </Bar>
        </BarChart>
      </ResponsiveContainer>
      <div className="mt-2 text-xs text-[color:var(--color-ink-faint)]">个股按申万行业，ETF 排除</div>
    </div>
  );
}
