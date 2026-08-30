import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import AllocationPie from "@/components/portfolio/AllocationPie";
import type { AllocationSlice } from "@/lib/types";

// jsdom 下 ResponsiveContainer 测不到尺寸、渲染不出 svg，
// 因此 mock recharts 组件，聚焦数据映射断言（与 TrendChart.test.tsx 同一模式）。
const { PieMock } = vi.hoisted(() => ({
  PieMock: vi.fn((_props: { data: AllocationSlice[]; dataKey: string; nameKey: string; children?: ReactNode }) => null),
}));

vi.mock("recharts", () => ({
  ResponsiveContainer: ({ children }: { children: ReactNode }) => (
    <div data-testid="allocation-chart">{children}</div>
  ),
  PieChart: ({ children }: { children?: ReactNode }) => <div>{children}</div>,
  Pie: PieMock,
  Cell: () => null,
  Tooltip: () => null,
  Legend: () => null,
}));

const slices: AllocationSlice[] = [
  { category: "权益", marketValue: 100000, ratio: 60 },
  { category: "现金", marketValue: 40000, ratio: 24 },
  { category: "其他", marketValue: 26000, ratio: 16 },
];

beforeEach(() => {
  PieMock.mockClear();
});

afterEach(() => {
  cleanup();
});

describe("AllocationPie", () => {
  it("allocation 为 null 时渲染空态且不渲染图表", () => {
    render(<AllocationPie allocation={null} />);
    expect(screen.getByText("资产配置")).toBeTruthy();
    expect(screen.getByText("暂无数据")).toBeTruthy();
    expect(PieMock).not.toHaveBeenCalled();
  });

  it("slices 为空数组时渲染空态", () => {
    render(<AllocationPie allocation={{ slices: [] }} />);
    expect(screen.getByText("暂无数据")).toBeTruthy();
    expect(PieMock).not.toHaveBeenCalled();
  });

  it("有数据时将切片交给饼图渲染", () => {
    render(<AllocationPie allocation={{ slices }} />);
    expect(screen.getByTestId("allocation-chart")).toBeTruthy();
    expect(PieMock).toHaveBeenCalledTimes(1);
    const props = PieMock.mock.calls[0][0];
    expect(props.data).toEqual(slices);
    expect(props.dataKey).toBe("marketValue");
    expect(props.nameKey).toBe("category");
    // 备注说明仍渲染
    expect(screen.getByText("ETF 归入权益")).toBeTruthy();
  });
});
