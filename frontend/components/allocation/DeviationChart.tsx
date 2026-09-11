"use client";

import { useMemo } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildBarOption } from "@/components/charts/optionBuilders";
import { getPalette } from "@/lib/chart-theme";
import type { DeviationView } from "@/lib/types";
import { ASSET_CLASS_LABELS } from "@/lib/allocationApi";

export default function DeviationChart({ deviation }: { deviation: DeviationView | null }) {
  // ?? [] 移入 memo 内、依赖 prop 本体：否则每次渲染新空数组使下游 memo 恒重算（exhaustive-deps）
  const slices = useMemo(() => deviation?.slices ?? [], [deviation]);
  const option = useMemo(() => {
    const p = getPalette();
    return buildBarOption(
      {
        specVersion: 1,
        type: "bar",
        title: "目标 vs 实际配置",
        unit: "%",
        categories: slices.map((s) => ASSET_CLASS_LABELS[s.assetClass]),
        series: [
          { name: "目标", data: slices.map((s) => s.targetWeight) },
          { name: "实际", data: slices.map((s) => s.actualWeight) },
        ],
      },
      // 旧配色：目标=ink-faint、实际=up（主题默认顺序是 [up, accent, ...]，此处必须覆盖）
      { seriesColors: [p.inkFaint, p.up] },
    );
  }, [slices]);
  if (slices.length === 0) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="deviation-chart">
        <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">目标 vs 实际配置</div>
        <div className="text-sm text-[color:var(--color-ink-faint)]">暂无生效方案，先套用模板或创建方案并设为生效</div>
      </div>
    );
  }
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="deviation-chart">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">目标 vs 实际配置</div>
      <EChart option={option} height={220} testid="deviation-chart-echart" />
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
