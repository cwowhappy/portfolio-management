// 纯函数：ChartSpec → ECOption。jsdom 可测（不触 canvas），是图表逻辑的测试主战场（05 §六）。
import type { ECOption } from "@/lib/echarts-setup";
import type { BarSpec, LineSpec, PieSpec } from "@/lib/chart-spec";

export interface BuilderStyle {
  /** 解析后的具体色值（来自 chart-theme palette）；不传则用主题配色循环 */
  seriesColors?: string[];
}

const colorAt = (style: BuilderStyle | undefined, i: number) =>
  style?.seriesColors?.[i % style.seriesColors!.length];

export function buildPieOption(spec: PieSpec, style?: BuilderStyle): ECOption {
  return {
    tooltip: { trigger: "item" },
    series: [
      {
        type: "pie",
        name: spec.title,
        radius: ["40%", "70%"],           // 环形，对齐旧 AllocationPie innerRadius/outerRadius 观感
        label: { show: false },
        data: spec.data.map((d, i) => {
          const color = colorAt(style, i);
          return color ? { name: d.name, value: d.value, itemStyle: { color } } : d;
        }),
      },
    ],
  };
}

export function buildBarOption(spec: BarSpec, style?: BuilderStyle): ECOption {
  const radius = spec.horizontal ? [0, 4, 4, 0] : [4, 4, 0, 0];
  const valueAxis = {
    type: "value" as const,
    axisLabel: spec.unit ? { formatter: `{value}${spec.unit}` } : undefined,
  };
  return {
    tooltip: { trigger: "axis" },
    legend: spec.series.length > 1 ? {} : undefined,
    grid: { left: 8, right: 8, top: 24, bottom: 0, containLabel: true },
    xAxis: spec.horizontal
      ? valueAxis
      : { type: "category", data: spec.categories },
    yAxis: spec.horizontal
      ? { type: "category", data: spec.categories }
      : valueAxis,
    series: spec.series.map((s, i) => ({
      type: "bar" as const,
      name: s.name,
      data: s.data,
      itemStyle: {
        borderRadius: radius,
        ...(colorAt(style, i) ? { color: colorAt(style, i) } : {}),
      },
    })),
  };
}

export function buildLineOption(spec: LineSpec, style?: BuilderStyle): ECOption {
  return {
    tooltip: { trigger: "axis" },
    legend: spec.series.length > 1 ? {} : undefined,
    grid: { left: 8, right: 8, top: 24, bottom: 0, containLabel: true },
    xAxis: { type: "category", data: spec.categories, boundaryGap: false },
    yAxis: {
      type: "value",
      axisLabel: spec.unit ? { formatter: `{value}${spec.unit}` } : undefined,
    },
    series: spec.series.map((s, i) => ({
      type: "line" as const,
      name: s.name,
      data: s.data,
      smooth: true,               // 对齐旧 type="monotone"
      showSymbol: false,          // 对齐旧 dot={false}
      connectNulls: false,        // 缺口留白
      lineStyle: { width: 1.5 },
      ...(s.area ? { areaStyle: { opacity: 0.15 } } : {}),
      ...(colorAt(style, i) ? { itemStyle: { color: colorAt(style, i) } } : {}),
    })),
  };
}
