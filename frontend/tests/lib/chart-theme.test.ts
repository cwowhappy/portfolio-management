import { describe, it, expect, vi, beforeEach } from "vitest";

// registerTheme 由 echarts/core 导出，chart-theme 以默认参数注入便于测试
// vi.hoisted：vitest 会把 vi.mock 与静态 import 一并提升到 const 之前，普通 const 会撞 TDZ
const registerThemeSpy = vi.hoisted(() => vi.fn());
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

  it("getPalette 按主题名缓存（同主题只解析一次）", async () => {
    const { getPalette } = await import("@/lib/chart-theme");
    document.documentElement.removeAttribute("data-theme");
    const a = getPalette();
    const b = getPalette();
    expect(a).toBe(b); // 同引用
  });

  it("getPalette SSR 守卫：SSR_FALLBACK 十键齐全且不破坏缓存同引用", async () => {
    const { getPalette, SSR_FALLBACK } = await import("@/lib/chart-theme");
    expect(Object.keys(SSR_FALLBACK).sort()).toEqual(Object.keys(PALETTE_VARS).sort());
    document.documentElement.removeAttribute("data-theme");
    expect(getPalette()).toBe(getPalette());
  });
});
