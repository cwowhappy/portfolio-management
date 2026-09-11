// 纯函数：ChartSpec → ECOption。jsdom 可测（不触 canvas），是图表逻辑的测试主战场（05 §六）。
import type { ECOption } from "@/lib/echarts-setup";
import type { BarSpec, CandlestickSpec, LineSpec, PieSpec } from "@/lib/chart-spec";

// BuilderStyle 追加两字段（向后兼容：P1 的 seriesColors 不动）
export interface BuilderStyle {
  /** 解析后的具体色值（来自 chart-theme palette）；不传则用主题配色循环 */
  seriesColors?: string[];
  /** 涨色（K 线实体/描边）；缺省回退 globals.css 深色默认——生产路径 ChartCard 必传运行时解析值 */
  up?: string;
  /** 跌色；同上 */
  down?: string;
}

/** 深色默认回退值（globals.css:14-15；仅测试/异常路径兜底，组件不得依赖） */
const FALLBACK_UP = "#e85b55";
const FALLBACK_DOWN = "#2fbe8f";

const colorAt = (style: BuilderStyle | undefined, i: number) =>
  style?.seriesColors?.[i % style.seriesColors!.length];

export function buildPieOption(spec: PieSpec, style?: BuilderStyle): ECOption {
  return {
    tooltip: { trigger: "item" },
    legend: {},                         // 底部定位/样式由 app 主题默认承载
    series: [
      {
        type: "pie",
        name: spec.title,
        radius: ["40%", "70%"],           // 环形，对齐旧 AllocationPie innerRadius/outerRadius 观感
        padAngle: 2,                      // 扇区间隙，对齐旧 paddingAngle={2}
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

export function buildCandlestickOption(spec: CandlestickSpec, style?: BuilderStyle): ECOption {
  const up = style?.up ?? FALLBACK_UP;
  const down = style?.down ?? FALLBACK_DOWN;
  return {
    animationDuration: 200,
    axisPointer: { link: [{ xAxisIndex: "all" }] },
    tooltip: { trigger: "axis", axisPointer: { type: "cross" } },
    legend: { data: ["日K", ...spec.mas?.map((m) => m.name) ?? []] },
    grid: [
      { left: 48, right: 16, top: 32, height: "58%" },
      { left: 48, right: 16, top: "74%", height: "16%" },   // 成交量副图
    ],
    xAxis: [
      { type: "category", data: spec.dates, boundaryGap: true },
      { type: "category", gridIndex: 1, data: spec.dates, axisLabel: { show: false } },
    ],
    yAxis: [
      { scale: true },
      { gridIndex: 1, axisLabel: { show: false }, splitLine: { show: false } },
    ],
    dataZoom: [
      { type: "inside", xAxisIndex: [0, 1], start: 60, end: 100 },
      { type: "slider", xAxisIndex: [0, 1], start: 60, end: 100 },
    ],
    series: [
      {
        type: "candlestick", name: "日K", data: spec.klines,
        itemStyle: { color: up, color0: down, borderColor: up, borderColor0: down },
      },
      ...(spec.mas?.map((m) => ({
        type: "line" as const, name: m.name, data: m.data,
        showSymbol: false, smooth: true, lineStyle: { width: 1 },
      })) ?? []),
      ...(spec.volumes ? [{
        type: "bar" as const, xAxisIndex: 1, yAxisIndex: 1, name: "成交量", data: spec.volumes,
      }] : []),
    ],
  };
}
