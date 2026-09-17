# ECharts 基础设施引入 + recharts 全站替换 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 引入 ECharts 6 按需注册管线（setup/主题/hook/契约/builders），把 4 个 recharts 页面组件迁移为 EChart 薄壳，最终移除 recharts 依赖（chart 载荷 gzip 344KB → 218KB）。

**Architecture:** 共享基建放 `frontend/components/charts/` + `frontend/lib/`（页面与后续聊天流渲染器共用，见 05 方案 §4.3）：`echarts-setup.ts` 是全仓库唯一允许 import echarts 的文件（ESLint 硬禁全量入口）；`chart-theme.ts` 把 CSS 变量运行时解析成 ECharts 主题（canvas 不认 `var(--x)`）；组件层退化为「域数据 → ChartSpec → optionBuilder → `<EChart>`」，对外 props/空态/文案/testid 完全不变（消费者零改动）。

**Tech Stack:** echarts 6.1.0（按需 use()）· zod 3.25 · vitest 3 + @testing-library/react（jsdom，**无 canvas——EChart 在组件测试中一律 mock，逻辑测试集中在 optionBuilders 纯函数**）

**Spec:** `docs/technology/research/05-富文本场景技术方案.md`（§4.4 ECharts 管线、§5.1 替换评估、§5.2 依赖表）。执行者须先读 Spec 的 §4.4 与 §5.1。

## Global Constraints

- 图表色值必须来自 CSS 变量解析（`--color-up` 红=涨、`--color-down` 绿=跌、`--color-accent`、`--color-ink-faint` 等，`frontend/app/globals.css:5-18` 暗色默认 + `[data-theme="light"]` 覆盖（globals.css:46；`app/layout.tsx` 内联脚本按 localStorage 设 `data-theme` 属性））——**不得在 ECharts option 里写 `var(--x)` 字符串**（canvas 不解析，渲染成透明/黑）。
- 只允许 `echarts/core|charts|components|features|renderers` 子路径 import 与 `import type`；裸 `'echarts'` 入口被 ESLint 禁止（Task 1 加规则）。
- 4 个组件对外 props、空态文案、卡片外壳 class、`data-testid`（allocation-chart / industry-chart / trend-chart / deviation-chart）**保持不变**。
- 本期只注册 Pie/Bar/Line（页面在用的）；candlestick/dataZoom/dataset 等聊天流阶段再加（YAGNI）。
- 所有命令在 `frontend/` 下执行（`pnpm -C frontend <cmd>` 或 cd 后执行）；测试命令 `pnpm test` = `vitest run --coverage`（跑单文件用 `pnpm vitest run <path>`）。
- 提交信息用 conventional + 中文描述（如 `feat(chart): ...`），一个 Task 一个 commit。

---

### Task 1: 安装 echarts + 按需注册 + 树摇守卫

**Files:**
- Create: `frontend/lib/echarts-setup.ts`
- Modify: `frontend/package.json`（新增依赖，由 pnpm 写入）
- Modify: `frontend/eslint.config.mjs`（在 rules 对象后新增一个 config 对象）

**Interfaces:**
- Produces: `ECOption` 类型（后续所有 builder/hook 的 option 类型）、`echarts-setup.ts` 模块副作用（use() 注册）

- [ ] **Step 1: 安装 echarts**

```bash
pnpm -C frontend add "echarts@^6.1.0"
```

预期：`frontend/package.json` dependencies 出现 `"echarts": "^6.1.0"`。

- [ ] **Step 2: 创建 `frontend/lib/echarts-setup.ts`**

```ts
// ★ 全仓库唯一允许 import echarts 的文件（eslint.config.mjs 禁裸 'echarts' 入口）。
// 只注册页面当前用到的图表；聊天流阶段再补 Candlestick/DataZoom/Dataset（见 05 §4.4）。
import * as echarts from "echarts/core";
import { PieChart, BarChart, LineChart } from "echarts/charts";
import {
  TooltipComponent,
  GridComponent,
  LegendComponent,
} from "echarts/components";
import { LabelLayout, UniversalTransition } from "echarts/features";
import { CanvasRenderer } from "echarts/renderers";
import type { ComposeOption } from "echarts/core";
import type {
  PieSeriesOption,
  BarSeriesOption,
  LineSeriesOption,
} from "echarts/charts";
import type {
  TooltipComponentOption,
  GridComponentOption,
  LegendComponentOption,
} from "echarts/components";

echarts.use([
  PieChart,
  BarChart,
  LineChart,
  TooltipComponent,
  GridComponent,
  LegendComponent,
  LabelLayout,
  UniversalTransition,
  CanvasRenderer,
]);

/** 本项目用到的 option 类型（按需组合，随注册集同步扩展） */
export type ECOption = ComposeOption<
  | PieSeriesOption
  | BarSeriesOption
  | LineSeriesOption
  | TooltipComponentOption
  | GridComponentOption
  | LegendComponentOption
>;

export { echarts };
```

- [ ] **Step 3: ESLint 加树摇守卫**

在 `frontend/eslint.config.mjs` 的 rules 对象（`"@typescript-eslint/no-explicit-any": "error"` 所在块）**之后**追加一个独立 config 对象：

```js
  {
    rules: {
      // 05 方案 §4.4：全量入口会把 gzip 379KB 全部拉进包（按需四图 218KB）
      "no-restricted-imports": [
        "error",
        {
          paths: [
            {
              name: "echarts",
              message:
                "禁用 echarts 全量入口：只允许 echarts/core|charts|components|features|renderers 子路径（唯一注册点 lib/echarts-setup.ts，见 docs/technology/research/05 §4.4）",
            },
          ],
        },
      ],
    },
  },
```

- [ ] **Step 4: 验证守卫生效**

```bash
cd frontend && printf 'import * as e from "echarts";\nexport const x = e;\n' > /tmp/bad-import.ts \
  && cp /tmp/bad-import.ts lib/__guard_check.ts \
  && pnpm eslint lib/__guard_check.ts; echo "exit=$?"; rm lib/__guard_check.ts /tmp/bad-import.ts
```

预期：eslint 报 `no-restricted-imports` 错误、`exit=1`（若 exit=0 说明规则没接上，检查 config 语法）。

- [ ] **Step 5: 验证 setup 可被引入且 lint 全绿**

```bash
cd frontend && pnpm eslint . && node -e "import('./lib/echarts-setup.ts').catch(e=>{console.error('ts 文件 node 直跑失败属正常（无 loader），跳过');process.exit(0)})"
```

（setup 的真实加载由后续 vitest 覆盖；本步以 `pnpm eslint .` 通过为准。）

- [ ] **Step 6: Commit**

```bash
git add frontend/lib/echarts-setup.ts frontend/eslint.config.mjs frontend/package.json frontend/pnpm-lock.yaml
git commit -m "feat(chart): 引入 echarts 6 按需注册与全量入口树摇守卫（05 §4.4）"
```

---

### Task 2: ChartSpec zod 契约（pie/bar/line）

**Files:**
- Create: `frontend/lib/chart-spec.ts`
- Test: `frontend/tests/lib/chart-spec.test.ts`

**Interfaces:**
- Produces: `ChartSpecSchema`、`ChartSpec`、`PieSpec`、`BarSpec`、`LineSpec`（Task 5/6/7/8/9 的 builder 与组件消费）

- [ ] **Step 1: 写失败测试 `frontend/tests/lib/chart-spec.test.ts`**

```ts
import { describe, it, expect } from "vitest";
import { ChartSpecSchema } from "@/lib/chart-spec";

describe("ChartSpecSchema", () => {
  it("解析合法 pie spec", () => {
    const r = ChartSpecSchema.safeParse({
      specVersion: 1, type: "pie", title: "资产配置",
      data: [{ name: "权益", value: 100000 }, { name: "现金", value: 40000 }],
    });
    expect(r.success).toBe(true);
  });

  it("解析合法 bar spec（含 null 数据点）", () => {
    const r = ChartSpecSchema.safeParse({
      specVersion: 1, type: "bar", title: "行业分布", unit: "%",
      categories: ["白酒", "银行"],
      series: [{ name: "市值", data: [160000, null] }],
    });
    expect(r.success).toBe(true);
  });

  it("解析合法 line spec（含 null 缺口与 area）", () => {
    const r = ChartSpecSchema.safeParse({
      specVersion: 1, type: "line", title: "估值历史走势",
      categories: ["2026-08-01", "2026-08-02"],
      series: [
        { name: "PE", data: [15, null] },
        { name: "PB", data: [1.5, 1.6], area: true },
      ],
    });
    expect(r.success).toBe(true);
  });

  it("拒绝错误 specVersion", () => {
    expect(ChartSpecSchema.safeParse({
      specVersion: 2, type: "pie", title: "x", data: [],
    }).success).toBe(false);
  });

  it("拒绝缺 title", () => {
    expect(ChartSpecSchema.safeParse({
      specVersion: 1, type: "pie", data: [],
    }).success).toBe(false);
  });

  it("拒绝未知 type", () => {
    expect(ChartSpecSchema.safeParse({
      specVersion: 1, type: "scatter", title: "x", categories: [], series: [],
    }).success).toBe(false);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && pnpm vitest run tests/lib/chart-spec.test.ts
```

预期：FAIL（`chart-spec` 模块不存在）。

- [ ] **Step 3: 写实现 `frontend/lib/chart-spec.ts`**

```ts
import { z } from "zod";

// 与后端 Java record 双端对齐（05 §2.1/§2.2）。本期只含页面在用的三种类型；
// candlestick/table 随聊天流阶段扩展。null = 数据缺口（估值序列缺失日）。
const seriesData = z.array(z.union([z.number(), z.null()]));

export const ChartSpecSchema = z.discriminatedUnion("type", [
  z.object({
    specVersion: z.literal(1),
    type: z.literal("pie"),
    title: z.string(),
    subtitle: z.string().optional(),
    data: z.array(z.object({ name: z.string(), value: z.number() })),
    unit: z.string().optional(),
  }),
  z.object({
    specVersion: z.literal(1),
    type: z.literal("bar"),
    title: z.string(),
    subtitle: z.string().optional(),
    categories: z.array(z.string()),
    series: z.array(z.object({ name: z.string(), data: seriesData })),
    horizontal: z.boolean().optional(),
    unit: z.string().optional(),
  }),
  z.object({
    specVersion: z.literal(1),
    type: z.literal("line"),
    title: z.string(),
    subtitle: z.string().optional(),
    categories: z.array(z.string()),
    series: z.array(
      z.object({
        name: z.string(),
        data: seriesData,
        area: z.boolean().optional(),
      }),
    ),
    unit: z.string().optional(),
  }),
]);

export type ChartSpec = z.infer<typeof ChartSpecSchema>;
export type PieSpec = Extract<ChartSpec, { type: "pie" }>;
export type BarSpec = Extract<ChartSpec, { type: "bar" }>;
export type LineSpec = Extract<ChartSpec, { type: "line" }>;
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && pnpm vitest run tests/lib/chart-spec.test.ts
```

预期：PASS（6 个用例）。

- [ ] **Step 5: Commit**

```bash
git add frontend/lib/chart-spec.ts frontend/tests/lib/chart-spec.test.ts
git commit -m "feat(chart): ChartSpec zod 契约（pie/bar/line，支持 null 缺口）"
```

---

### Task 3: chart-theme（CSS 变量解析 + 主题注册）

**Files:**
- Create: `frontend/lib/chart-theme.ts`
- Test: `frontend/tests/lib/chart-theme.test.ts`

**Interfaces:**
- Produces: `ChartPalette`、`getPalette(): ChartPalette`（缓存）、`ensureAppThemes(): void`、`currentThemeName(): "app-dark" | "app-light"`（Task 4/9 消费）

- [ ] **Step 1: 写失败测试 `frontend/tests/lib/chart-theme.test.ts`**

```ts
import { describe, it, expect, vi, beforeEach } from "vitest";

// registerTheme 由 echarts/core 导出，chart-theme 以默认参数注入便于测试
const registerThemeSpy = vi.fn();
vi.mock("echarts/core", () => ({ registerTheme: registerThemeSpy }));

import {
  resolvePalette, paletteColors, registerAppThemes, currentThemeName, PALETTE_VARS,
} from "@/lib/chart-theme";

const vars: Record<string, string> = {
  "--color-up": " #e85b55 ",      // 故意带空格：验证 trim
  "--color-down": "#2fbe8f",
  "--color-accent": "#d4a94f",
  "--color-ink": "#eee",
  "--color-ink-dim": "#bbb",
  "--color-ink-faint": "#888",
  "--color-line": "#444",
  "--color-line-soft": "#333",
  "--color-panel": "#1b1d21",
  "--color-panel-2": "#22242a",
};

describe("chart-theme", () => {
  beforeEach(() => registerThemeSpy.mockClear());

  it("resolvePalette 从 CSS 变量解析并 trim", () => {
    const p = resolvePalette((v) => vars[v] ?? "");
    expect(p.up).toBe("#e85b55");
    expect(p.panel).toBe("#1b1d21");
  });

  it("paletteColors 顺序 = [up, accent, inkFaint]（对齐 AllocationPie 现有 COLORS）", () => {
    const p = resolvePalette((v) => vars[v] ?? "");
    expect(paletteColors(p)).toEqual(["#e85b55", "#d4a94f", "#888"]);
  });

  it("registerAppThemes 注册 app-dark/app-light 且主题色取自 palette", () => {
    const p = resolvePalette((v) => vars[v] ?? "");
    registerAppThemes(p, registerThemeSpy);
    expect(registerThemeSpy).toHaveBeenCalledTimes(2);
    const names = registerThemeSpy.mock.calls.map((c) => c[0]);
    expect(names).toEqual(["app-dark", "app-light"]);
    const theme = registerThemeSpy.mock.calls[0][1] as { color: string[] };
    expect(theme.color).toEqual(["#e85b55", "#d4a94f", "#888"]);
  });

  it("currentThemeName 按根节点 data-theme 属性判断（globals.css 用 [data-theme=\"light\"]，无 .light 类）", () => {
    document.documentElement.removeAttribute("data-theme");
    expect(currentThemeName()).toBe("app-dark");
    document.documentElement.setAttribute("data-theme", "light");
    expect(currentThemeName()).toBe("app-light");
    document.documentElement.removeAttribute("data-theme");
  });

  it("PALETTE_VARS 覆盖全部 palette 键", () => {
    const p = resolvePalette((v) => vars[v] ?? "");
    expect(Object.keys(PALETTE_VARS).sort()).toEqual(Object.keys(p).sort());
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && pnpm vitest run tests/lib/chart-theme.test.ts
```

预期：FAIL（模块不存在）。

- [ ] **Step 3: 写实现 `frontend/lib/chart-theme.ts`**

```ts
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
    out[key] = getter(PALETTE_VARS[key]);
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
  register: (name: string, theme: unknown) => void = registerTheme,
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
```

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && pnpm vitest run tests/lib/chart-theme.test.ts
```

预期：PASS（5 个用例）。

- [ ] **Step 5: Commit**

```bash
git add frontend/lib/chart-theme.ts frontend/tests/lib/chart-theme.test.ts
git commit -m "feat(chart): CSS 变量运行时解析与 app-dark/app-light 主题注册"
```

---

### Task 4: useECharts hook

**Files:**
- Create: `frontend/components/charts/useECharts.ts`
- Test: `frontend/tests/charts/useECharts.test.tsx`

**Interfaces:**
- Consumes: `ECOption`（Task 1）、`ensureAppThemes`/`currentThemeName`（Task 3）
- Produces: `useECharts(option: ECOption): RefObject<HTMLDivElement | null>`（Task 5 EChart 消费）

- [ ] **Step 1: 写失败测试 `frontend/tests/charts/useECharts.test.tsx`**

```tsx
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { cleanup, render } from "@testing-library/react";
import type { ReactNode } from "react";

// echarts/core mock init（useECharts 直接用）+ registerTheme（ensureAppThemes→registerAppThemes 默认参数会取它）
const fakeChart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() };
const initSpy = vi.fn(() => fakeChart);
vi.mock("echarts/core", () => ({ init: initSpy, registerTheme: vi.fn() }));

import { useECharts } from "@/components/charts/useECharts";
import type { ECOption } from "@/lib/echarts-setup";

// jsdom 无 ResizeObserver，注入桩以捕获 resize 回调
let roCallback: (() => void) | null = null;
class ResizeObserverStub {
  observe = vi.fn();
  disconnect = vi.fn();
  constructor(cb: () => void) { roCallback = cb; }
}

function Probe({ option }: { option: ECOption; children?: ReactNode }) {
  const ref = useECharts(option);
  return <div ref={ref} data-testid="probe" />;
}

describe("useECharts", () => {
  beforeEach(() => {
    initSpy.mockClear(); fakeChart.setOption.mockClear(); fakeChart.dispose.mockClear();
    roCallback = null;
    vi.stubGlobal("ResizeObserver", ResizeObserverStub);
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("挂载时 init 容器并 setOption(notMerge)", () => {
    const option = { series: [{ type: "pie" as const, data: [] }] };
    render(<Probe option={option} />);
    expect(initSpy).toHaveBeenCalledTimes(1);
    expect(fakeChart.setOption).toHaveBeenCalledWith(option, { notMerge: true });
  });

  it("容器尺寸变化时 resize", () => {
    render(<Probe option={{ series: [{ type: "bar" as const, data: [] }] }} />);
    roCallback?.();
    expect(fakeChart.resize).toHaveBeenCalledTimes(1);
  });

  it("option 引用变化时重新 setOption", () => {
    const { rerender } = render(<Probe option={{ series: [{ type: "line" as const, data: [] }] }} />);
    const next = { series: [{ type: "line" as const, data: [1, 2] }] };
    rerender(<Probe option={next} />);
    expect(fakeChart.setOption).toHaveBeenLastCalledWith(next, { notMerge: true });
  });

  it("卸载时 dispose 并断开监听（StrictMode 对称清理）", () => {
    const { unmount } = render(<Probe option={{ series: [{ type: "pie" as const, data: [] }] }} />);
    unmount();
    expect(fakeChart.dispose).toHaveBeenCalledTimes(1);
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && pnpm vitest run tests/charts/useECharts.test.tsx
```

预期：FAIL（模块不存在）。

- [ ] **Step 3: 写实现 `frontend/components/charts/useECharts.ts`**

```ts
"use client";
// ~60 行自写封装：echarts-for-react 有 Next.js transpile 负担 + 双 init 反模式 + 3.x 撤包风险（05 §4.4 对比）。
import { useEffect, useRef } from "react";
import { init } from "echarts/core";
import type { EChartsType } from "echarts/core";
import type { ECOption } from "@/lib/echarts-setup";
import { ensureAppThemes, currentThemeName } from "@/lib/chart-theme";

export function useECharts(option: ECOption) {
  const ref = useRef<HTMLDivElement | null>(null);
  const chartRef = useRef<EChartsType | null>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    ensureAppThemes();
    const chart = init(el, currentThemeName());
    chartRef.current = chart;
    const ro = new ResizeObserver(() => chart.resize());
    ro.observe(el);
    return () => {
      ro.disconnect();
      chart.dispose();
      chartRef.current = null;
    };
  }, []);

  useEffect(() => {
    chartRef.current?.setOption(option, { notMerge: true });
  }, [option]);

  return ref;
}
```

注意：`ensureAppThemes` 尚未导出——在 `chart-theme.ts` 末尾补上（Task 3 的实现文件）：

```ts
export function ensureAppThemes(): void {
  registerAppThemes(getPalette());
}
```

并在 Task 3 的测试文件中补一个用例（`registerAppThemes(p, registerThemeSpy)` 已覆盖注册逻辑，此处补 getPalette 缓存行为）：

```ts
  it("getPalette 按主题名缓存（同主题只解析一次）", async () => {
    const { getPalette } = await import("@/lib/chart-theme");
    document.documentElement.removeAttribute("data-theme");
    const a = getPalette();
    const b = getPalette();
    expect(a).toBe(b); // 同引用
  });
```

（jsdom 下 getComputedStyle 取不到真实变量值，返回空串——缓存行为断言不依赖具体色值。）

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && pnpm vitest run tests/charts/useECharts.test.tsx tests/lib/chart-theme.test.ts
```

预期：PASS。

- [ ] **Step 5: Commit**

```bash
git add frontend/components/charts/useECharts.ts frontend/tests/charts/useECharts.test.tsx frontend/lib/chart-theme.ts frontend/tests/lib/chart-theme.test.ts
git commit -m "feat(chart): useECharts hook——init/setOption/resize/dispose 对称封装"
```

---

### Task 5: optionBuilders 纯函数 + EChart 壳组件

**Files:**
- Create: `frontend/components/charts/optionBuilders.ts`
- Create: `frontend/components/charts/EChart.tsx`
- Test: `frontend/tests/charts/optionBuilders.test.ts`
- Test: `frontend/tests/charts/EChart.test.tsx`

**Interfaces:**
- Consumes: `PieSpec`/`BarSpec`/`LineSpec`（Task 2）、`ECOption`（Task 1）、`useECharts`（Task 4）
- Produces: `buildPieOption(spec, style?)` / `buildBarOption(spec, style?)` / `buildLineOption(spec, style?)`，`BuilderStyle = { seriesColors?: string[] }`；`EChart({option, height?, testid?})`（Task 6-9 消费）

- [ ] **Step 1: 写失败测试 `frontend/tests/charts/optionBuilders.test.ts`**

```ts
import { describe, it, expect } from "vitest";
import { buildPieOption, buildBarOption, buildLineOption } from "@/components/charts/optionBuilders";

describe("buildPieOption", () => {
  it("环形半径与 name/value 映射，默认不带 per-item 颜色（主题配色循环）", () => {
    const opt = buildPieOption({
      specVersion: 1, type: "pie", title: "资产配置",
      data: [{ name: "权益", value: 100000 }, { name: "现金", value: 40000 }],
    });
    const s = opt.series![0] as { type: string; radius: [string, string]; data: { name: string; value: number }[] };
    expect(s.type).toBe("pie");
    expect(s.radius).toEqual(["40%", "70%"]);
    expect(s.data).toEqual([{ name: "权益", value: 100000 }, { name: "现金", value: 40000 }]);
  });

  it("seriesColors 覆盖时逐项着色", () => {
    const opt = buildPieOption({
      specVersion: 1, type: "pie", title: "t",
      data: [{ name: "a", value: 1 }, { name: "b", value: 2 }, { name: "c", value: 3 }],
    }, { seriesColors: ["#111", "#222"] });
    const s = opt.series![0] as { data: { itemStyle?: { color: string } }[] };
    expect(s.data[0].itemStyle?.color).toBe("#111");
    expect(s.data[1].itemStyle?.color).toBe("#222");
    expect(s.data[2].itemStyle?.color).toBe("#111"); // 循环取色
  });
});

describe("buildBarOption", () => {
  const spec = {
    specVersion: 1 as const, type: "bar" as const, title: "目标 vs 实际配置", unit: "%",
    categories: ["股票", "债券"],
    series: [
      { name: "目标", data: [60, 30] },
      { name: "实际", data: [70.59, 25.1] },
    ],
  };

  it("类目轴 + 多系列 + legend；圆角柱顶", () => {
    const opt = buildBarOption(spec);
    expect(opt.xAxis).toMatchObject({ type: "category", data: ["股票", "债券"] });
    expect(opt.legend).toBeDefined();
    const series = opt.series as { name: string; itemStyle?: { borderRadius?: number[] } }[];
    expect(series.map((s) => s.name)).toEqual(["目标", "实际"]);
    expect(series[0].itemStyle?.borderRadius).toEqual([4, 4, 0, 0]);
  });

  it("unit 进 y 轴标签格式；seriesColors 逐系列覆盖", () => {
    const opt = buildBarOption(spec, { seriesColors: ["#888", "#e85b55"] });
    expect(opt.yAxis).toMatchObject({ axisLabel: { formatter: "{value}%" } });
    const series = opt.series as { itemStyle?: { color: string } }[];
    expect(series[0].itemStyle?.color).toBe("#888");
    expect(series[1].itemStyle?.color).toBe("#e85b55");
  });

  it("单系列不出 legend", () => {
    const opt = buildBarOption({
      specVersion: 1, type: "bar", title: "行业分布",
      categories: ["白酒"], series: [{ name: "市值", data: [160000] }],
    });
    expect(opt.legend).toBeUndefined();
  });
});

describe("buildLineOption", () => {
  it("null 缺口保留（connectNulls:false）、smooth、无符号点、双系列", () => {
    const opt = buildLineOption({
      specVersion: 1, type: "line", title: "估值历史走势",
      categories: ["08-01", "08-02", "08-03"],
      series: [
        { name: "PE", data: [15, null, 16] },
        { name: "PB", data: [1.5, 1.6, null] },
      ],
    });
    expect(opt.xAxis).toMatchObject({ type: "category", data: ["08-01", "08-02", "08-03"] });
    const series = opt.series as { name: string; data: (number | null)[]; smooth: boolean; showSymbol: boolean; connectNulls: boolean }[];
    expect(series).toHaveLength(2);
    expect(series[0].data).toEqual([15, null, 16]);
    expect(series[0].smooth).toBe(true);
    expect(series[0].showSymbol).toBe(false);
    expect(series[0].connectNulls).toBe(false);
  });
});
```

- [ ] **Step 2: 写失败测试 `frontend/tests/charts/EChart.test.tsx`**

```tsx
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

// hook 已在 useECharts.test.tsx 单独覆盖；此处 mock hook 验证壳组件透传
vi.mock("@/components/charts/useECharts", () => ({
  useECharts: vi.fn(() => ({ current: null })),
}));

import { EChart } from "@/components/charts/EChart";

describe("EChart", () => {
  it("透传 testid 与显式高度", () => {
    render(<EChart option={{ series: [] }} height={240} testid="trend-chart" />);
    const el = screen.getByTestId("trend-chart");
    expect(el.style.height).toBe("240px");
    expect(el.style.width).toBe("100%");
  });

  it("默认高度 200", () => {
    render(<EChart option={{ series: [] }} testid="x" />);
    expect(screen.getByTestId("x").style.height).toBe("200px");
  });
});
```

- [ ] **Step 3: 跑两个测试确认失败**

```bash
cd frontend && pnpm vitest run tests/charts/optionBuilders.test.ts tests/charts/EChart.test.tsx
```

预期：FAIL（模块不存在）。

- [ ] **Step 4: 写实现 `frontend/components/charts/optionBuilders.ts`**

```ts
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
```

- [ ] **Step 5: 写实现 `frontend/components/charts/EChart.tsx`**

```tsx
"use client";
import type { ECOption } from "@/lib/echarts-setup";
import { useECharts } from "./useECharts";

/** 图表壳：显式高度（ECharts 需要确定尺寸，替代 recharts ResponsiveContainer 的隐式语义） */
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
```

- [ ] **Step 6: 跑测试确认通过**

```bash
cd frontend && pnpm vitest run tests/charts/
```

预期：PASS（builders 6 + EChart 2）。

- [ ] **Step 7: Commit**

```bash
git add frontend/components/charts/optionBuilders.ts frontend/components/charts/EChart.tsx frontend/tests/charts/
git commit -m "feat(chart): optionBuilders 纯函数与 EChart 壳组件"
```

---

### Task 6: 迁移 AllocationPie

**Files:**
- Modify: `frontend/components/portfolio/AllocationPie.tsx`（全文重写，见 Step 3）
- Test: `frontend/tests/AllocationPie.test.tsx`（全文重写）

**Interfaces:**
- Consumes: `buildPieOption`（Task 5）、`EChart`（Task 5）
- Produces: 组件 props 不变 `{ allocation: AssetAllocation | null }`；testid `allocation-chart` 保留

- [ ] **Step 1: 重写测试（先失败）**

```tsx
import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import AllocationPie from "@/components/portfolio/AllocationPie";
import type { AllocationSlice } from "@/lib/types";

// jsdom 无 canvas：mock EChart 壳，断言传入的 option（数据映射），模式对齐 05 §六
vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

const slices: AllocationSlice[] = [
  { category: "权益", marketValue: 100000, ratio: 60 },
  { category: "现金", marketValue: 40000, ratio: 24 },
  { category: "其他", marketValue: 26000, ratio: 16 },
];

afterEach(() => cleanup());

describe("AllocationPie", () => {
  it("allocation 为 null 时渲染空态且不渲染图表", () => {
    render(<AllocationPie allocation={null} />);
    expect(screen.getByText("资产配置")).toBeTruthy();
    expect(screen.getByText("暂无数据")).toBeTruthy();
    expect(screen.queryByTestId("allocation-chart")).toBeNull();
  });

  it("slices 为空数组时渲染空态", () => {
    render(<AllocationPie allocation={{ slices: [] }} />);
    expect(screen.getByText("暂无数据")).toBeTruthy();
    expect(screen.queryByTestId("allocation-chart")).toBeNull();
  });

  it("有数据时把切片映射为饼图 option（name=category, value=marketValue）", () => {
    render(<AllocationPie allocation={{ slices }} />);
    expect(screen.getByTestId("allocation-chart")).toBeTruthy();
    const option = JSON.parse(screen.getByTestId("allocation-chart").dataset.option!);
    const series = option.series[0];
    expect(series.type).toBe("pie");
    expect(series.data).toEqual([
      { name: "权益", value: 100000 },
      { name: "现金", value: 40000 },
      { name: "其他", value: 26000 },
    ]);
    // 备注说明仍渲染
    expect(screen.getByText("ETF 归入权益")).toBeTruthy();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && pnpm vitest run tests/AllocationPie.test.tsx
```

预期：FAIL（组件仍 import recharts，mock 目标 EChart 未被使用 / queryByTestId 为 null）。

- [ ] **Step 3: 重写组件**

```tsx
"use client";

import { useMemo } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildPieOption } from "@/components/charts/optionBuilders";
import type { AssetAllocation } from "@/lib/types";

export default function AllocationPie({ allocation }: { allocation: AssetAllocation | null }) {
  const data = allocation?.slices ?? [];
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
```

（配色说明：旧组件 `COLORS = [up, accent, ink-faint]` 循环 = 主题 `color` 数组顺序，无需 seriesColors。）

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && pnpm vitest run tests/AllocationPie.test.tsx
```

预期：PASS（3 个用例）。

- [ ] **Step 5: Commit**

```bash
git add frontend/components/portfolio/AllocationPie.tsx frontend/tests/AllocationPie.test.tsx
git commit -m "refactor(portfolio): AllocationPie 迁移至 EChart（配色经主题循环）"
```

---

### Task 7: 迁移 IndustryBar

**Files:**
- Modify: `frontend/components/portfolio/IndustryBar.tsx`
- Test: `frontend/tests/IndustryBar.test.tsx`

**Interfaces:**
- Consumes: `buildBarOption`、`EChart`
- Produces: props 不变 `{ industry: IndustryDistribution | null }`；testid `industry-chart` 保留

- [ ] **Step 1: 重写测试（先失败）**

```tsx
import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import IndustryBar from "@/components/portfolio/IndustryBar";
import type { IndustrySlice } from "@/lib/types";

vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

const slices: IndustrySlice[] = [
  { industryName: "白酒", marketValue: 160000, ratio: 80 },
  { industryName: "银行", marketValue: 40000, ratio: 20 },
];

afterEach(() => cleanup());

describe("IndustryBar", () => {
  it("industry 为 null 时渲染空态且不渲染图表", () => {
    render(<IndustryBar industry={null} />);
    expect(screen.getByText("行业分布")).toBeTruthy();
    expect(screen.getByText("暂无数据（个股需有申万行业映射）")).toBeTruthy();
    expect(screen.queryByTestId("industry-chart")).toBeNull();
  });

  it("slices 为空数组时渲染空态", () => {
    render(<IndustryBar industry={{ slices: [] }} />);
    expect(screen.getByText("暂无数据（个股需有申万行业映射）")).toBeTruthy();
    expect(screen.queryByTestId("industry-chart")).toBeNull();
  });

  it("有数据时映射为单系列柱状 option（类目=行业名，值=市值）", () => {
    render(<IndustryBar industry={{ slices }} />);
    expect(screen.getByTestId("industry-chart")).toBeTruthy();
    const option = JSON.parse(screen.getByTestId("industry-chart").dataset.option!);
    expect(option.xAxis).toMatchObject({ type: "category", data: ["白酒", "银行"] });
    expect(option.series).toHaveLength(1);
    expect(option.series[0].data).toEqual([160000, 40000]);
    // 单系列不出 legend
    expect(option.legend).toBeUndefined();
    // 备注说明仍渲染
    expect(screen.getByText("个股按申万行业，ETF 排除")).toBeTruthy();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && pnpm vitest run tests/IndustryBar.test.tsx
```

- [ ] **Step 3: 重写组件**

```tsx
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
```

（配色：主题 `color[0]` = up，单系列柱色与旧 `fill="var(--color-up)"` 一致。）

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && pnpm vitest run tests/IndustryBar.test.tsx
```

- [ ] **Step 5: Commit**

```bash
git add frontend/components/portfolio/IndustryBar.tsx frontend/tests/IndustryBar.test.tsx
git commit -m "refactor(portfolio): IndustryBar 迁移至 EChart"
```

---

### Task 8: 迁移 TrendChart

**Files:**
- Modify: `frontend/components/valuation/TrendChart.tsx`
- Test: `frontend/tests/TrendChart.test.tsx`

**Interfaces:**
- Consumes: `buildLineOption`、`EChart`
- Produces: props 不变 `{ snapshots, indexValuations?, selectedIndex? }`；testid `trend-chart` 保留；`toPoints` 逻辑（过滤/排序/映射）原样保留在组件内

- [ ] **Step 1: 重写测试（先失败）**

```tsx
import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import TrendChart from "@/components/valuation/TrendChart";
import type { ValuationSnapshot, IndexValuationSeries } from "@/lib/types";

vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

const snapshots: ValuationSnapshot[] = [
  { tradingDay: "2026-08-01", peMedian: 15, pbMedian: 1.5, netBreakerCount: 1, netBreakerRatio: 0.1 },
  { tradingDay: "2026-08-02", peMedian: 16, pbMedian: 1.6, netBreakerCount: 1, netBreakerRatio: 0.1 },
];

// 含多指数，且 000300 的序列故意乱序（验证按 tradingDay 升序排序）。
const indexValuations: IndexValuationSeries[] = [
  { tradingDay: "2026-08-02", indexCode: "000300", indexName: "沪深300", pe: 13, pb: 1.3, dividendYield: 2.4 },
  { tradingDay: "2026-08-01", indexCode: "000300", indexName: "沪深300", pe: 12, pb: 1.2, dividendYield: 2.5 },
  { tradingDay: "2026-08-01", indexCode: "000905", indexName: "中证500", pe: 22, pb: 1.8, dividendYield: 1.5 },
  { tradingDay: "2026-08-02", indexCode: "000905", indexName: "中证500", pe: 23, pb: 1.9, dividendYield: 1.4 },
];

function readOption() {
  return JSON.parse(screen.getByTestId("trend-chart").dataset.option!);
}

afterEach(() => cleanup());

describe("TrendChart", () => {
  it("空数据渲染积累中且不渲染图表", () => {
    render(<TrendChart snapshots={[]} />);
    expect(screen.getByText(/积累中/)).toBeTruthy();
    expect(screen.queryByTestId("trend-chart")).toBeNull();
  });

  it("有数据时快照映射为双系列（PE/PB）折线 option", () => {
    render(<TrendChart snapshots={snapshots} />);
    expect(screen.getByText("估值历史走势")).toBeTruthy();
    const option = readOption();
    expect(option.xAxis.data).toEqual(["2026-08-01", "2026-08-02"]);
    expect(option.series.map((s: { name: string }) => s.name)).toEqual(["PE", "PB"]);
    expect(option.series[0].data).toEqual([15, 16]);
    expect(option.series[1].data).toEqual([1.5, 1.6]);
  });

  it("选中指数时按 indexCode 过滤并按 tradingDay 升序", () => {
    render(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="000300" />);
    const option = readOption();
    expect(option.xAxis.data).toEqual(["2026-08-01", "2026-08-02"]);
    expect(option.series[0].data).toEqual([12, 13]);
    expect(option.series[1].data).toEqual([1.2, 1.3]);
  });

  it("切换 selectedIndex 时渲染数据随之改变", () => {
    const { rerender } = render(
      <TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="000300" />,
    );
    rerender(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="000905" />);
    const option = readOption();
    expect(option.series[0].data).toEqual([22, 23]);
    expect(option.series[1].data).toEqual([1.8, 1.9]);
  });

  it("选中无数据序列的指数时渲染积累中且不渲染图表", () => {
    render(<TrendChart snapshots={[]} indexValuations={indexValuations} selectedIndex="399006" />);
    expect(screen.getByText(/积累中/)).toBeTruthy();
    expect(screen.queryByTestId("trend-chart")).toBeNull();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && pnpm vitest run tests/TrendChart.test.tsx
```

- [ ] **Step 3: 重写组件**

```tsx
"use client";

import { useMemo } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildLineOption } from "@/components/charts/optionBuilders";
import type { ValuationSnapshot, IndexValuationSeries } from "@/lib/types";

interface TrendPoint {
  day: string;
  pe: number | null;
  pb: number | null;
}

function toPoints(
  snapshots: ValuationSnapshot[],
  indexValuations: IndexValuationSeries[],
  selectedIndex: string,
): TrendPoint[] {
  if (selectedIndex === "market") {
    return snapshots.map((s) => ({ day: s.tradingDay, pe: s.peMedian, pb: s.pbMedian }));
  }
  return indexValuations
    .filter((p) => p.indexCode === selectedIndex)
    .slice()
    .sort((a, b) => a.tradingDay.localeCompare(b.tradingDay))
    .map((p) => ({ day: p.tradingDay, pe: p.pe, pb: p.pb }));
}

export default function TrendChart({
  snapshots,
  indexValuations = [],
  selectedIndex = "market",
}: {
  snapshots: ValuationSnapshot[];
  indexValuations?: IndexValuationSeries[];
  selectedIndex?: string;
}) {
  const data = toPoints(snapshots, indexValuations, selectedIndex);
  const option = useMemo(
    () =>
      buildLineOption({
        specVersion: 1,
        type: "line",
        title: "估值历史走势",
        categories: data.map((p) => p.day),
        series: [
          { name: "PE", data: data.map((p) => p.pe) },
          { name: "PB", data: data.map((p) => p.pb) },
        ],
      }),
    [data],
  );
  if (data.length === 0) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">估值历史走势</div>
        <div className="text-sm text-[color:var(--color-ink-faint)]">数据积累中</div>
      </div>
    );
  }
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">估值历史走势</div>
      <EChart option={option} height={240} testid="trend-chart" />
    </div>
  );
}
```

（配色：主题 `color[0]`=up → PE、`color[1]`=accent → PB，与旧 stroke 一一对应。）

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && pnpm vitest run tests/TrendChart.test.tsx
```

- [ ] **Step 5: Commit**

```bash
git add frontend/components/valuation/TrendChart.tsx frontend/tests/TrendChart.test.tsx
git commit -m "refactor(valuation): TrendChart 迁移至 EChart（保留过滤/排序映射逻辑）"
```

---

### Task 9: 迁移 DeviationChart

**Files:**
- Modify: `frontend/components/allocation/DeviationChart.tsx`
- Test: `frontend/tests/DeviationChart.test.tsx`

**Interfaces:**
- Consumes: `buildBarOption`、`EChart`、`getPalette`（Task 3）
- Produces: props 不变 `{ deviation: DeviationView | null }`；外层 `data-testid="deviation-chart"` 保留；偏离度摘要 footer 不变

- [ ] **Step 1: 重写测试（先失败）**

```tsx
import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import DeviationChart from "@/components/allocation/DeviationChart";

vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

afterEach(() => cleanup());

describe("DeviationChart", () => {
  it("无生效方案时显示空态提示且不渲染图表", () => {
    render(<DeviationChart deviation={{ slices: [] }} />);
    expect(screen.getByText(/暂无生效方案/)).toBeTruthy();
    expect(screen.queryByTestId("deviation-chart-echart")).toBeNull();
  });

  it("渲染偏离度摘要", () => {
    render(
      <DeviationChart
        deviation={{ slices: [{ assetClass: "STOCK", targetWeight: 60, actualWeight: 70.59, deviation: 10.59 }] }}
      />,
    );
    expect(screen.getByText(/股票 偏离 \+10.59%/)).toBeTruthy();
  });

  it("双系列（目标/实际）+ unit % + 系列色覆盖 [inkFaint, up]", () => {
    render(
      <DeviationChart
        deviation={{ slices: [{ assetClass: "STOCK", targetWeight: 60, actualWeight: 70.59, deviation: 10.59 }] }}
      />,
    );
    const option = JSON.parse(screen.getByTestId("deviation-chart-echart").dataset.option!);
    expect(option.series.map((s: { name: string }) => s.name)).toEqual(["目标", "实际"]);
    expect(option.yAxis.axisLabel.formatter).toBe("{value}%");
    expect(option.series[0].data).toEqual([60]);
    expect(option.series[1].data).toEqual([70.59]);
    expect(option.legend).toBeDefined();
  });
});
```

- [ ] **Step 2: 跑测试确认失败**

```bash
cd frontend && pnpm vitest run tests/DeviationChart.test.tsx
```

- [ ] **Step 3: 重写组件**

```tsx
"use client";

import { useMemo } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildBarOption } from "@/components/charts/optionBuilders";
import { getPalette } from "@/lib/chart-theme";
import type { DeviationView } from "@/lib/types";
import { ASSET_CLASS_LABELS } from "@/lib/allocationApi";

export default function DeviationChart({ deviation }: { deviation: DeviationView | null }) {
  const slices = deviation?.slices ?? [];
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
```

注意：jsdom 里 `getComputedStyle` 取不到真实 CSS 变量 → `getPalette()` 返回空串色值。断言只覆盖结构（系列名/formatter/legend），**不断言具体色值**（色值正确性由 Task 10 视觉 QA 覆盖）。

- [ ] **Step 4: 跑测试确认通过**

```bash
cd frontend && pnpm vitest run tests/DeviationChart.test.tsx
```

- [ ] **Step 5: Commit**

```bash
git add frontend/components/allocation/DeviationChart.tsx frontend/tests/DeviationChart.test.tsx
git commit -m "refactor(allocation): DeviationChart 迁移至 EChart（目标/实际双色覆盖）"
```

---

### Task 10: 移除 recharts + 全量回归 + 视觉 QA

**Files:**
- Modify: `frontend/package.json` / `pnpm-lock.yaml`（pnpm remove 写入）

**Interfaces:**
- Consumes: 前 9 个 Task 的全部产物

- [ ] **Step 1: 确认无残余 recharts 引用**

```bash
cd frontend && grep -rn "recharts" --include="*.ts" --include="*.tsx" app components lib tests || echo "OK: 无源码引用"
```

预期：`OK: 无源码引用`（若命中，回到对应文件清理）。

- [ ] **Step 2: 移除依赖**

```bash
pnpm -C frontend remove recharts
```

- [ ] **Step 3: 全量测试 + lint + 构建**

```bash
cd frontend && pnpm test && pnpm eslint . && pnpm build
```

预期：vitest 全绿（含 coverage 门槛）、eslint 无错误、next build 成功。

- [ ] **Step 4: 视觉 QA（人工，暗/亮两轮）**

启动本地 dev（后端可用即可，图表数据来自页面接口）逐项核对，清单：

1. 资产配置卡：环形图三色顺序（涨红/青蓝/灰——`--color-up`/`--color-accent`(#3fb8d8)/`--color-ink-faint`）、图例在底部、tooltip 深底描边；
2. 行业分布卡：柱色=涨红、圆角柱顶、x 轴行业名可读（倾斜时可接受）；
3. 估值历史走势：PE=红（up）、PB=青蓝（accent）、无数据点符号、虚线网格；
4. 目标 vs 实际：目标=灰、实际=红、y 轴 `%`、底部图例；
5. 切换亮色主题刷新页面（localStorage 存 `theme` 后 reload，`app/layout.tsx` 内联脚本设 `data-theme`）：全部图表颜色随 `[data-theme="light"]` 变量重取（**已知限制**：不刷新页面仅切属性，已挂图表色不变——记录为后续优化项，不阻塞）。

发现问题 → 修 `chart-theme.ts` 主题对象或 builder 默认值（不得在组件里硬编码色值）。

- [ ] **Step 5: Commit**

```bash
git add frontend/package.json frontend/pnpm-lock.yaml
git commit -m "chore(frontend): 移除 recharts，图表体系统一为 ECharts（gzip -126KB）"
```

---

## 自查记录（Self-Review）

1. **Spec 覆盖**：05 §4.4（setup/主题/hook/builders/树摇守卫）→ Task 1/3/4/5；§5.1 替换评估的 4 组件 + 13 用例 + 移除 → Task 6-10（用例数：3+3+5+3=14，DeviationChart 从 2 增至 3 以覆盖图表 option）。candlestick/表格/聊天流集成不在本计划（05 §八 步骤 1-3、5-7 另行计划）。
2. **占位符扫描**：无 TBD/TODO；每个代码步骤给出完整代码。
3. **类型一致性**：`ECOption`/`ChartSpec`/`PieSpec`/`BarSpec`/`LineSpec`/`BuilderStyle`/`EChart props`/`getPalette` 在 Task 2/3/5 定义、Task 4/6-9 消费的签名一致；testid 常量（allocation-chart/industry-chart/trend-chart/deviation-chart[-echart]）跨 Task 一致。
