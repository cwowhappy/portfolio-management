"use client";
import type { ECOption } from "@/lib/echarts-setup";
import { useECharts, type ChartEvents } from "./useECharts";

/** 图表壳：显式高度（ECharts 需要确定尺寸，替代旧图表库 ResponsiveContainer 的隐式撑高语义） */
export function EChart({
  option,
  height = 200,
  testid,
  onEvents,
}: {
  option: ECOption;
  height?: number;
  testid?: string;
  /** 事件绑定（须传 memoized 对象，引用不稳会每渲染重绑）；MS-10 F09 点击跳转首用。 */
  onEvents?: ChartEvents;
}) {
  const ref = useECharts(option, onEvents);
  return <div ref={ref} data-testid={testid} style={{ width: "100%", height }} />;
}
