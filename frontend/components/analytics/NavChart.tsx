"use client";

import { useMemo } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildLineOption } from "@/components/charts/optionBuilders";

/** Board 归一化后的走势序列：组合与各基准已按日期交集对齐、各除以自身窗口首值 ×1000。 */
export interface NormalizedNav {
  dates: string[];
  portfolio: number[];
  benchmarks: { code: string; name: string; values: number[] }[];
}

/** 归一化净值对比图：组合 + 各基准 line series，legend 显隐（buildLineOption 多系列自动开 legend）。 */
export default function NavChart({ data }: { data: NormalizedNav }) {
  const option = useMemo(
    () =>
      buildLineOption({
        specVersion: 1,
        type: "line",
        title: "净值对比",
        categories: data.dates,
        series: [
          { name: "组合", data: data.portfolio },
          ...data.benchmarks.map((b) => ({ name: b.name, data: b.values })),
        ],
      }),
    [data],
  );
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">净值对比（首日 = 1000）</div>
      <EChart option={option} height={300} testid="nav-chart" />
    </div>
  );
}
