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

describe("candlestick 变体", () => {
  const valid = {
    specVersion: 1, type: "candlestick", title: "600519 贵州茅台 日K",
    symbol: "600519 贵州茅台", period: "day",
    dates: ["2026-09-09", "2026-09-10"],
    klines: [[1800.5, 1850.2, 1790.1, 1860.0], [1850.2, 1840.0, 1830.5, 1870.3]],
    volumes: [120_000, 98_000],
    mas: [{ name: "MA5", data: [null, 1830.1] }],           // null = 预热缺口，与 dates 对齐
  };
  it("解析合法 candlestick（含 null MA 预热）", () => {
    expect(ChartSpecSchema.safeParse(valid).success).toBe(true);
  });
  it("klines 必须是 4 元数值元组（[开,收,低,高]）", () => {
    expect(ChartSpecSchema.safeParse({ ...valid, klines: [[1, 2, 3]] }).success).toBe(false);
    expect(ChartSpecSchema.safeParse({ ...valid, klines: [["1", "2", "3", "4"]] }).success).toBe(false);
  });
  it("period/day-week-month 之外的字符串仍可解析（后端契约展示字段）", () => {
    expect(ChartSpecSchema.safeParse({ ...valid, period: "week" }).success).toBe(true);
  });
});

describe("table 变体（单一 ChartSpec 的变体，非独立 schema）", () => {
  const valid = {
    specVersion: 1, type: "table", title: "贵州茅台 财务指标",
    columns: [
      { key: "reportDate", label: "报告期" },
      { key: "eps", label: "每股收益EPS", align: "right" as const, sortable: true },
    ],
    rows: [
      { reportDate: "2026-06-30", eps: 24.2, roi: null },
      { reportDate: "2026-03-31", eps: 11.8 },
    ],
  };
  it("解析合法 table（列定义 + 行记录，值允许 string/number/null）", () => {
    expect(ChartSpecSchema.safeParse(valid).success).toBe(true);
  });
  it("行值为对象/数组时拒绝", () => {
    expect(ChartSpecSchema.safeParse({ ...valid, rows: [{ reportDate: { v: 1 } }] }).success).toBe(false);
  });
  it("列 align 只允许 left/right/center", () => {
    expect(ChartSpecSchema.safeParse({ ...valid, columns: [{ key: "a", label: "A", align: "middle" }] }).success).toBe(false);
  });
});
