"use client";

import { PieChart, Pie, Cell, Tooltip, ResponsiveContainer, Legend } from "recharts";
import type { AssetAllocation } from "@/lib/types";

const COLORS = ["var(--color-up)", "var(--color-amber)", "var(--color-ink-faint)"];

export default function AllocationPie({ allocation }: { allocation: AssetAllocation | null }) {
  const data = allocation?.slices ?? [];
  if (data.length === 0) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">资产配置</div>
        <div className="text-sm text-[color:var(--color-ink-faint)]">暂无数据</div>
      </div>
    );
  }
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">资产配置</div>
      <ResponsiveContainer width="100%" height={200}>
        <PieChart>
          <Pie data={data} dataKey="marketValue" nameKey="category" innerRadius={45} outerRadius={70} paddingAngle={2}>
            {data.map((_, i) => <Cell key={i} fill={COLORS[i % COLORS.length]} />)}
          </Pie>
          <Tooltip contentStyle={{ background: "var(--color-panel)", border: "1px solid var(--color-line)" }} />
          <Legend />
        </PieChart>
      </ResponsiveContainer>
    </div>
  );
}
