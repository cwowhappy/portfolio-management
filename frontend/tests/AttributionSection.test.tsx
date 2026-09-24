import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import AttributionSection from "@/components/analytics/AttributionSection";
import type { Attribution } from "@/lib/types";

// jsdom 无 canvas：mock EChart 壳，经 data-option 断言传入的 option（模式对齐 AnalyticsBoard/TrendChart 测试）
vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

// 后端契约：数值 toPlainString 字符串（setScale(10)，如 "0.0015000000"）；
// 银行 |0.003|+|0|=0.003 < 食品饮料 |0.0015|+|0.006|=0.0075 → 排序后食品饮料在前。
const attribution: Attribution = {
  windowStart: "2026-01-05", windowEnd: "2026-01-07",
  rows: [
    { industry: "801120", industryName: "食品饮料", allocation: "0.0015000000", selection: "0.0060000000" },
    { industry: "801780", industryName: "银行", allocation: "0.0030000000", selection: "0.0000000000" },
  ],
  cashAllocation: "-0.0005000000", totalExcess: "0.0100000000", residual: "0.0000000100",
  unmappedValueShare: "0.0000000000",
};

function readOption() {
  return JSON.parse(screen.getByTestId("attribution-chart").dataset.option!);
}

afterEach(cleanup);

describe("AttributionSection", () => {
  it("渲染行业双柱图与汇总行：数值字符串转 Number 后进图（×100 百分点）", () => {
    render(<AttributionSection attribution={attribution} />);
    expect(screen.getByTestId("attribution-section")).toBeTruthy();
    expect(screen.getByTestId("attribution-chart")).toBeTruthy(); // EChart mock 壳
    expect(screen.getByText("行业贡献（对沪深300）")).toBeTruthy(); // 卡片标题
    // 汇总行：0.0100000000 → 1.00%；-0.0005000000 → -0.05%
    expect(screen.getByText(/总超额/)).toBeTruthy();
    expect(screen.getByText("1.00%")).toBeTruthy();
    expect(screen.getByText(/现金贡献/)).toBeTruthy();
    expect(screen.getByText("-0.05%")).toBeTruthy();
    expect(screen.getByText(/残差/)).toBeTruthy();
    expect(screen.queryByText(/未映射/)).toBeNull(); // unmappedValueShare=0 不提示
    // 图 option：按 |配置|+|选择| 降序排列，数值 ×100 进图
    const option = readOption();
    expect(option.xAxis.data).toEqual(["食品饮料", "银行"]);
    expect(option.series.map((s: { name: string }) => s.name)).toEqual(["配置贡献", "选择贡献"]);
    expect(option.series[0].data[0]).toBeCloseTo(0.15, 10); // 0.0015×100
    expect(option.series[0].data[1]).toBeCloseTo(0.3, 10); // 0.003×100
    expect(option.series[1].data[0]).toBeCloseTo(0.6, 10); // 0.006×100
    expect(option.series[1].data[1]).toBeCloseTo(0, 10);
  });

  it("unmappedValueShare > 0 时显示未映射行业持仓占比提示", () => {
    render(<AttributionSection attribution={{ ...attribution, unmappedValueShare: "0.0050000000" }} />);
    // label 与值同节点（brief 标记形态），整体匹配：0.0050000000 → 0.50%
    expect(screen.getByText(/未映射行业持仓占比 0\.50%/)).toBeTruthy();
  });

  it("industryName 为 null 的桶用行业码兜底作类目", () => {
    render(<AttributionSection attribution={{
      ...attribution,
      rows: [{ industry: "801120", industryName: null, allocation: "0.0015000000", selection: "0.0060000000" }],
    }} />);
    expect(readOption().xAxis.data).toEqual(["801120"]);
  });
});
