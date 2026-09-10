import { describe, it, expect } from "vitest";
import { buildPieOption, buildBarOption, buildLineOption } from "@/components/charts/optionBuilders";

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
