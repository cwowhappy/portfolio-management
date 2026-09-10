"use client";
import type { ECOption } from "@/lib/echarts-setup";
import { useECharts } from "./useECharts";

/** 图表壳：显式高度（ECharts 需要确定尺寸，替代旧图表库 ResponsiveContainer 的隐式撑高语义） */
export function EChart({
  option,
  height = 200,
  testid,
}: {
  option: ECOption;
  height?: number;
  testid?: string;
}) {
  const ref = useECharts(option);
  return <div ref={ref} data-testid={testid} style={{ width: "100%", height }} />;
}
