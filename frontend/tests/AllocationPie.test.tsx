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
