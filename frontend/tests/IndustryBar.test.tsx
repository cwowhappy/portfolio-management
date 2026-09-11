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
