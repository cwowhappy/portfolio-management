// ECharts canvas 不解析 CSS 变量：主题色必须在运行时 getComputedStyle 解析成具体色值（05 §4.4）。
// 解析发生在函数内（不在模块顶层），避免 SSR 期触 window/document。
import { registerTheme } from "echarts/core";

export interface ChartPalette {
  up: string;
  down: string;
  accent: string;
  ink: string;
  inkDim: string;
  inkFaint: string;
  line: string;
  lineSoft: string;
  panel: string;
  panel2: string;
}

export const PALETTE_VARS: Record<keyof ChartPalette, string> = {
  up: "--color-up",
  down: "--color-down",
  accent: "--color-accent",
  ink: "--color-ink",
  inkDim: "--color-ink-dim",
  inkFaint: "--color-ink-faint",
  line: "--color-line",
  lineSoft: "--color-line-soft",
  panel: "--color-panel",
  panel2: "--color-panel-2",
};

type VarGetter = (cssVar: string) => string;

const defaultGetter: VarGetter = (v) =>
  getComputedStyle(document.documentElement).getPropertyValue(v).trim();

export function resolvePalette(getter: VarGetter = defaultGetter): ChartPalette {
  const out = {} as Record<keyof ChartPalette, string>;
  for (const key of Object.keys(PALETTE_VARS) as (keyof ChartPalette)[]) {
    // trim 在 resolvePalette 内做（而非仅 defaultGetter）：注入任意 getter 也保证输出无空白
    out[key] = getter(PALETTE_VARS[key]).trim();
  }
  return out as ChartPalette;
}

/** 系列默认配色顺序（对齐旧 AllocationPie COLORS = [up, accent, ink-faint]） */
export function paletteColors(p: ChartPalette): string[] {
  return [p.up, p.accent, p.inkFaint];
}

/** ECharts 主题对象：轴/网格/legend/tooltip 的暗亮共用底色（色值已随 CSS 变量解析） */
export function buildAppTheme(p: ChartPalette) {
  return {
    color: paletteColors(p),
    textStyle: { color: p.inkFaint, fontSize: 12 },
    legend: { bottom: 0, textStyle: { color: p.inkDim } },
    tooltip: {
      backgroundColor: p.panel,
      borderColor: p.line,
      textStyle: { color: p.ink },
    },
    xAxis: {
      axisLine: { lineStyle: { color: p.lineSoft } },
      axisLabel: { color: p.inkFaint, fontSize: 12 },
    },
    yAxis: {
      axisLine: { lineStyle: { color: p.lineSoft } },
      axisLabel: { color: p.inkFaint, fontSize: 12 },
      splitLine: { lineStyle: { color: p.lineSoft, type: "dashed" as const } },
    },
  };
}

export function registerAppThemes(
  palette: ChartPalette,
  // typeof registerTheme 而非 (name, theme: unknown)：strictFunctionTypes 下参数逆变，
  // registerTheme 无法赋给 unknown 参数版签名（会挂 next build）；vi.fn() 因 any 参数天然可赋值。
  register: typeof registerTheme = registerTheme,
): void {
  register("app-dark", buildAppTheme(palette));
  register("app-light", buildAppTheme(palette));
}

export function currentThemeName(): "app-dark" | "app-light" {
  return typeof document !== "undefined" &&
    document.documentElement.getAttribute("data-theme") === "light"
    ? "app-light"
    : "app-dark";
}

// getPalette 按主题名缓存（暗亮切换后首次重取）；
// 已知限制：切换主题后已挂图表不自动重绘色值，需触发重渲染（视觉 QA 项，见 Task 10）。
let cached: { name: string; palette: ChartPalette } | null = null;

export function getPalette(): ChartPalette {
  const name = currentThemeName();
  if (cached?.name === name) return cached.palette;
  const palette = resolvePalette();
  cached = { name, palette };
  return palette;
}
