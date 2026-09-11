import { describe, it, expect } from "vitest";
import { buildPieOption, buildBarOption, buildLineOption } from "@/components/charts/optionBuilders";
import { buildCandlestickOption } from "@/components/charts/optionBuilders";
import type { CandlestickSpec } from "@/lib/chart-spec";

describe("buildPieOption", () => {
  it("环形半径与 name/value 映射，默认不带 per-item 颜色（主题配色循环）", () => {
    const opt = buildPieOption({
      specVersion: 1, type: "pie", title: "资产配置",
      data: [{ name: "权益", value: 100000 }, { name: "现金", value: 40000 }],
    });
    const s = (opt.series as { type: string; radius: [string, string]; data: { name: string; value: number }[] }[])[0];
    expect(s.type).toBe("pie");
    expect(s.radius).toEqual(["40%", "70%"]);
    expect(s.data).toEqual([{ name: "权益", value: 100000 }, { name: "现金", value: 40000 }]);
  });

  it("seriesColors 覆盖时逐项着色", () => {
    const opt = buildPieOption({
      specVersion: 1, type: "pie", title: "t",
      data: [{ name: "a", value: 1 }, { name: "b", value: 2 }, { name: "c", value: 3 }],
    }, { seriesColors: ["#111", "#222"] });
    const s = (opt.series as { data: { itemStyle?: { color: string } }[] }[])[0];
    expect(s.data[0].itemStyle?.color).toBe("#111");
    expect(s.data[1].itemStyle?.color).toBe("#222");
    expect(s.data[2].itemStyle?.color).toBe("#111"); // 循环取色
  });

  it("出 legend（底部定位由主题承载）并保留扇区间隙 padAngle=2", () => {
    const opt = buildPieOption({
      specVersion: 1, type: "pie", title: "资产配置",
      data: [{ name: "权益", value: 1 }, { name: "现金", value: 2 }],
    });
    expect(opt.legend).toBeDefined();
    expect((opt.series as { padAngle?: number }[])[0].padAngle).toBe(2);
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

describe("buildCandlestickOption", () => {
  const spec: CandlestickSpec = {
    specVersion: 1 as const, type: "candlestick" as const, title: "600519 贵州茅台 日K",
    symbol: "600519 贵州茅台", period: "day",
    dates: ["09-09", "09-10", "09-11"],
    klines: [[1800, 1850, 1790, 1860], [1850, 1840, 1830, 1870], [1840, 1880, 1835, 1890]],
    volumes: [120_000, 98_000, 111_000],
    mas: [{ name: "MA5", data: [null, 1830.1, 1855.0] }],
  };
  const style = { up: "#e85b55", down: "#2fbe8f" };

  it("主副图双 grid、双 xAxis、inside+slider dataZoom 联动", () => {
    const opt = buildCandlestickOption(spec, style);
    expect(opt.grid).toHaveLength(2);
    expect(opt.xAxis).toHaveLength(2);
    const zoom = opt.dataZoom as { type: string; xAxisIndex: number[] }[];
    expect(zoom.map((z) => z.type)).toEqual(["inside", "slider"]);
    expect(zoom.every((z) => z.xAxisIndex.includes(0) && z.xAxisIndex.includes(1))).toBe(true);
  });

  it("烛台 series 涨红跌绿四色 itemStyle；klines 原样透传（[开,收,低,高]）", () => {
    const opt = buildCandlestickOption(spec, style);
    const candle = (opt.series as { type: string }[]).find((s) => s.type === "candlestick") as {
      type: string; data: number[][]; itemStyle: { color: string; color0: string; borderColor: string; borderColor0: string };
    };
    expect(candle.data).toEqual(spec.klines);
    expect(candle.itemStyle).toMatchObject({ color: "#e85b55", color0: "#2fbe8f", borderColor: "#e85b55", borderColor0: "#2fbe8f" });
  });

  it("MA 线与成交量副图叠加；无 volumes 时不出成交量 series", () => {
    const opt = buildCandlestickOption(spec, style);
    const names = (opt.series as { name?: string }[]).map((s) => s.name);
    expect(names).toContain("MA5");
    expect(names).toContain("成交量");
    const noVol = buildCandlestickOption({ ...spec, volumes: undefined }, style);
    expect((noVol.series as { name?: string }[]).map((s) => s.name)).not.toContain("成交量");
  });

  it("style 缺省时回退深色主题字面值（ChartCard 生产路径必传 getPalette 解析值）", () => {
    const opt = buildCandlestickOption(spec);
    const candle = (opt.series as { type: string }[]).find((s) => s.type === "candlestick") as { type: string; itemStyle: { color: string } };
    expect(candle.itemStyle.color).toBe("#e85b55");
  });
});
