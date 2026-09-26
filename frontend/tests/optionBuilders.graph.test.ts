import { describe, expect, it } from "vitest";
import { buildGraphOption, type ChainGraphSpec } from "@/components/charts/optionBuilders";

// 已知答案样例：三 tier 各一节点 + 未上市节点（symbol 区分）
const spec: ChainGraphSpec = {
  kind: "chain",
  name: "锂电池",
  nodes: [
    { id: "1", name: "赣锋锂业", tier: "UPSTREAM", listed: true, stockCode: "002460", sub: "锂矿·上游" },
    { id: "2", name: "宁德时代", tier: "MIDSTREAM", listed: true, stockCode: "300750" },
    { id: "3", name: "示例康源生物", tier: "DOWNSTREAM", listed: false },
  ],
};

describe("buildGraphOption", () => {
  const opt = buildGraphOption(spec);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const series = opt.series as any[];

  it("单 graph 系列 + force 布局（repulsion 200 / edgeLength 60~120）", () => {
    expect(series).toHaveLength(1);
    expect(series[0].type).toBe("graph");
    expect(series[0].layout).toBe("force");
    expect(series[0].force.repulsion).toBe(200);
    expect(series[0].force.edgeLength).toEqual([60, 120]);
  });

  it("无 edges：环节归属由 category 分组表达，不连边（设计规格 §六决策）", () => {
    expect(series[0].links).toBeUndefined();
    expect(series[0].edges).toBeUndefined();
  });

  it("category 三档按 tier 映射（上游/中游/下游），legend 与 categories 同名", () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const legend = opt.legend as any;
    expect(legend.data).toEqual(["上游", "中游", "下游"]);
    expect(series[0].categories).toEqual([
      { name: "上游" }, { name: "中游" }, { name: "下游" },
    ]);
    expect(series[0].data.map((n: { category: number }) => n.category)).toEqual([0, 1, 2]);
  });

  it("tier 三色默认值（SSR_FALLBACK 字面值：accent/up/inkDim）且可覆盖", () => {
    expect(series[0].color).toEqual(["#3fb8d8", "#e85b55", "#96a4b7"]);
    const custom = buildGraphOption(spec, ["#111111", "#222222", "#333333"]);
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect(((custom.series as any[])[0].color)).toEqual(["#111111", "#222222", "#333333"]);
  });

  it("listed 节点 symbol 与未上市不同；label 常显", () => {
    expect(series[0].data[0].symbol).not.toBe(series[0].data[2].symbol);
    expect(series[0].label.show).toBe(true);
  });

  it("节点携带 id/stockCode 供点击跳转；tooltip formatter 出名称与附注", () => {
    expect(series[0].data[0].id).toBe("1");
    expect(series[0].data[0].stockCode).toBe("002460");
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const fmt = opt.tooltip?.formatter as any;
    expect(fmt({ data: { name: "赣锋锂业", sub: "锂矿·上游" } })).toBe("赣锋锂业｜锂矿·上游");
    expect(fmt({ data: { name: "宁德时代" } })).toBe("宁德时代");
  });

  it("空节点不抛错（空系列仍成立）", () => {
    const empty = buildGraphOption({ kind: "chain", name: "空链", nodes: [] });
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    expect((empty.series as any[])[0].data).toEqual([]);
  });
});
