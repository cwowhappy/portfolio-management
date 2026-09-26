import { describe, expect, it } from "vitest";
import { buildScatterOption, type LandscapeSpec } from "@/components/charts/optionBuilders";

// 已知答案样例：市值亿元、轮次序取 FundingRound 声明序（B=5、D=9、IPO=12）
const spec: LandscapeSpec = {
  kind: "landscape",
  listed: [
    { name: "立讯精密", code: "002475", marketCapYi: 2000 },
    { name: "中芯国际", code: "688981", marketCapYi: 5000 },
  ],
  unlisted: [
    { name: "示例华芯科技", round: "B轮", roundOrder: 5 },
    { name: "示例光子科技", round: "D轮", roundOrder: 9 },
  ],
};

describe("buildScatterOption", () => {
  const opt = buildScatterOption(spec);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const series = opt.series as any[];

  it("双色 scatter 系列：上市（IPO 轮带）与未上市（各自轮次带）", () => {
    expect(series).toHaveLength(2);
    expect(series.map((s) => s.type)).toEqual(["scatter", "scatter"]);
    expect(series[0].name).toBe("上市公司");
    expect(series[1].name).toBe("未上市策展");
    expect(series[0].itemStyle.color).not.toBe(series[1].itemStyle.color);
  });

  it("x=序、y=轮次序（上市恒落 IPO=12 带）、size=市值亿平方根映射（最大 32）", () => {
    // 上市：y 恒 12；size = 10 + 22*sqrt(cap/maxCap)，保序映射——立讯(2000) 序 0、中芯(5000) 序 1
    expect(series[0].data[0].value[0]).toBe(0);
    expect(series[0].data[0].value[1]).toBe(12);
    expect(series[0].data[0].value[2]).toBeCloseTo(10 + 22 * Math.sqrt(2000 / 5000), 5);
    expect(series[0].data[1].value[0]).toBe(1);
    expect(series[0].data[1].value[1]).toBe(12);
    expect(series[0].data[1].value[2]).toBe(32);          // 最大市值 → 上限 32
    // data 项带 code 供点击跳转
    expect(series[0].data[1].code).toBe("688981");
    // 未上市：y=roundOrder 原值（yAxis inverse 使轮次越高越上）、固定尺寸
    expect(series[1].data.map((d: { value: number[] }) => [d.value[0], d.value[1], d.value[2]]))
      .toEqual([[0, 5, 14], [1, 9, 14]]);
  });

  it("y 轴反转（轮次越高越上）且轴标签映射轮次中文", () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const yAxis = opt.yAxis as any;
    expect(yAxis.inverse).toBe(true);
    expect(yAxis.axisLabel.formatter(5)).toBe("B轮");
    expect(yAxis.axisLabel.formatter(12)).toBe("IPO");
    expect(yAxis.axisLabel.formatter(9)).toBe("D轮");
  });

  it("tooltip formatter 出名称/市值或轮次", () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const fmt = opt.tooltip?.formatter as any;
    expect(fmt({ data: { name: "中芯国际", marketCapYi: 5000 } })).toBe("中芯国际｜市值 5000 亿");
    expect(fmt({ data: { name: "示例光子科技", round: "D轮" } })).toBe("示例光子科技｜轮次 D轮");
  });

  it("空数据不抛错（两空系列仍成立）", () => {
    const empty = buildScatterOption({ kind: "landscape", listed: [], unlisted: [] });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((empty.series as any[]).map((s) => s.data)).toEqual([[], []]);
  });
});
