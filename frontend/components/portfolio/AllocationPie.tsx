"use client";

import { useMemo } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildPieOption } from "@/components/charts/optionBuilders";
import type { AssetAllocation } from "@/lib/types";

export default function AllocationPie({ allocation }: { allocation: AssetAllocation | null }) {
  // ?? [] 移入 memo 内、依赖 prop 本体：否则每次渲染新空数组使下游 memo 恒重算（exhaustive-deps）
  const data = useMemo(() => allocation?.slices ?? [], [allocation]);
  const option = useMemo(
    () =>
      buildPieOption({
        specVersion: 1,
        type: "pie",
        title: "资产配置",
        data: data.map((s) => ({ name: s.category, value: s.marketValue })),
      }),
    [data],
  );
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
      <EChart option={option} height={200} testid="allocation-chart" />
      <div className="mt-2 text-xs text-[color:var(--color-ink-faint)]">ETF 归入权益</div>
    </div>
  );
}
