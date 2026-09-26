// 纯函数：ChartSpec → ECOption。jsdom 可测（不触 canvas），是图表逻辑的测试主战场（05 §六）。
import type { ECOption } from "@/lib/echarts-setup";
import type { BarSpec, CandlestickSpec, LineSpec, PieSpec } from "@/lib/chart-spec";
import { FUNDING_ROUNDS, roundLabel } from "@/lib/fundingRounds";

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

// —— 竞争格局气泡（MS-10 F09，页面私有 spec——不进 chat ChartSpec 联合，设计规格 §九#6）——

/** 竞争格局 spec：上市市值气泡 + 未上市轮次标签（roundOrder 取 FundingRound 声明序）。 */
export type LandscapeSpec = {
  kind: "landscape";
  listed: Array<{ name: string; code: string; marketCapYi: number }>;
  unlisted: Array<{ name: string; round: string; roundOrder: number }>;
};

/** 双系列默认色（chart-theme SSR_FALLBACK 字面值；运行时由调用方传 colors 覆盖）。 */
const LANDSCAPE_COLORS: [string, string] = ["#3fb8d8", "#e85b55"];
/** 上市气泡恒落 IPO 轮带（FundingRound 声明序，含 mirror 锁定）。 */
const IPO_ORDER = FUNDING_ROUNDS.findIndex((r) => r.value === "IPO");
/** 未上市气泡固定尺寸（无市值数据，不参与 size 映射）。 */
const UNLISTED_SIZE = 14;

/**
 * 竞争格局双色气泡：x=序、y=轮次序（yAxis inverse——轮次越高越上，上市恒在 IPO 带）、
 * 上市 size=市值亿平方根映射（10~32），tooltip 出名称/市值或轮次。纯函数，jsdom 可测。
 */
export function buildScatterOption(spec: LandscapeSpec, colors: [string, string] = LANDSCAPE_COLORS): ECOption {
  const maxCap = Math.max(...spec.listed.map((d) => d.marketCapYi), 1);
  // formatter 参数按 ECharts 回调的宽松形态收（unknown + 内部窄化），data 项字段由本函数自造
  type BubbleDatum = { name?: string; marketCapYi?: number; round?: string };
  const asDatum = (p: unknown): BubbleDatum =>
    (p as { data?: BubbleDatum }).data ?? {};
  return {
    tooltip: {
      trigger: "item",
      formatter: (p: unknown) => {
        const d = asDatum(p);
        return d.marketCapYi != null
          ? `${d.name}｜市值 ${d.marketCapYi} 亿`
          : `${d.name}｜轮次 ${d.round}`;
      },
    },
    legend: { data: ["上市公司", "未上市策展"] },
    grid: { left: 8, right: 16, top: 32, bottom: 24, containLabel: true },
    xAxis: { type: "value", show: false, min: -0.5, max: Math.max(spec.listed.length, spec.unlisted.length, 1) - 0.5 },
    yAxis: {
      type: "value",
      inverse: true, // 轮次序越大越靠上（IPO/晚期在最上）
      min: -1,
      max: FUNDING_ROUNDS.length,
      axisLabel: { formatter: (v: number) => roundLabel(FUNDING_ROUNDS[v]?.value ?? String(v)) },
    },
    series: [
      {
        type: "scatter",
        name: "上市公司",
        itemStyle: { color: colors[0], opacity: 0.85 },
        data: spec.listed.map((d, i) => ({
          name: d.name,
          code: d.code, // 点击跳行情台（/market?code=）
          marketCapYi: d.marketCapYi,
          value: [i, IPO_ORDER, 10 + 22 * Math.sqrt(d.marketCapYi / maxCap)],
        })),
      },
      {
        type: "scatter",
        name: "未上市策展",
        itemStyle: { color: colors[1], opacity: 0.85 },
        label: {
          show: true,
          position: "right",
          formatter: (p: unknown) => asDatum(p).round ?? "",
        },
        data: spec.unlisted.map((d, i) => ({
          name: d.name,
          round: d.round,
          value: [i, d.roundOrder, UNLISTED_SIZE],
        })),
      },
    ],
  };
}
