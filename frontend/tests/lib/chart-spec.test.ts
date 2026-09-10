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
