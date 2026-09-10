"use client";

import { useMemo } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildBarOption } from "@/components/charts/optionBuilders";
import type { IndustryDistribution } from "@/lib/types";

export default function IndustryBar({ industry }: { industry: IndustryDistribution | null }) {
  const data = industry?.slices ?? [];
  const option = useMemo(
    () =>
      buildBarOption({
        specVersion: 1,
        type: "bar",
        title: "行业分布",
        categories: data.map((s) => s.industryName),
        series: [{ name: "市值", data: data.map((s) => s.marketValue) }],
      }),
    [data],
  );
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
      <EChart option={option} height={200} testid="industry-chart" />
      <div className="mt-2 text-xs text-[color:var(--color-ink-faint)]">个股按申万行业，ETF 排除</div>
    </div>
  );
}
