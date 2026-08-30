import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import IndustryBar from "@/components/portfolio/IndustryBar";
import type { IndustrySlice } from "@/lib/types";

// jsdom 下 ResponsiveContainer 测不到尺寸、渲染不出 svg，
// 因此 mock recharts 组件，聚焦数据映射断言（与 TrendChart.test.tsx 同一模式）。
const { BarChartMock } = vi.hoisted(() => ({
  BarChartMock: vi.fn((_props: { data: IndustrySlice[]; children?: ReactNode }) => null),
}));

vi.mock("recharts", () => ({
  ResponsiveContainer: ({ children }: { children: ReactNode }) => (
    <div data-testid="industry-chart">{children}</div>
  ),
  BarChart: BarChartMock,
  Bar: () => null,
  XAxis: () => null,
  YAxis: () => null,
  Tooltip: () => null,
  Cell: () => null,
}));

const slices: IndustrySlice[] = [
  { industryName: "白酒", marketValue: 160000, ratio: 80 },
  { industryName: "银行", marketValue: 40000, ratio: 20 },
];

beforeEach(() => {
  BarChartMock.mockClear();
});

afterEach(() => {
  cleanup();
});

describe("IndustryBar", () => {
  it("industry 为 null 时渲染空态且不渲染图表", () => {
    render(<IndustryBar industry={null} />);
    expect(screen.getByText("行业分布")).toBeTruthy();
    expect(screen.getByText("暂无数据（个股需有申万行业映射）")).toBeTruthy();
    expect(BarChartMock).not.toHaveBeenCalled();
  });

  it("slices 为空数组时渲染空态", () => {
    render(<IndustryBar industry={{ slices: [] }} />);
    expect(screen.getByText("暂无数据（个股需有申万行业映射）")).toBeTruthy();
    expect(BarChartMock).not.toHaveBeenCalled();
  });

  it("有数据时将切片交给柱状图渲染", () => {
    render(<IndustryBar industry={{ slices }} />);
    expect(screen.getByTestId("industry-chart")).toBeTruthy();
    expect(BarChartMock).toHaveBeenCalledTimes(1);
    expect(BarChartMock.mock.calls[0][0].data).toEqual(slices);
    // 备注说明仍渲染
    expect(screen.getByText("个股按申万行业，ETF 排除")).toBeTruthy();
  });
});
