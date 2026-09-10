import { afterEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import DeviationChart from "@/components/allocation/DeviationChart";

vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

afterEach(() => cleanup());

describe("DeviationChart", () => {
  it("无生效方案时显示空态提示且不渲染图表", () => {
    render(<DeviationChart deviation={{ slices: [] }} />);
    expect(screen.getByText(/暂无生效方案/)).toBeTruthy();
    expect(screen.queryByTestId("deviation-chart-echart")).toBeNull();
  });

  it("渲染偏离度摘要", () => {
    render(
      <DeviationChart
        deviation={{ slices: [{ assetClass: "STOCK", targetWeight: 60, actualWeight: 70.59, deviation: 10.59 }] }}
      />,
    );
    expect(screen.getByText(/股票 偏离 \+10.59%/)).toBeTruthy();
  });

  it("双系列（目标/实际）+ unit % + 系列色覆盖 [inkFaint, up]", () => {
    render(
      <DeviationChart
        deviation={{ slices: [{ assetClass: "STOCK", targetWeight: 60, actualWeight: 70.59, deviation: 10.59 }] }}
      />,
    );
    const option = JSON.parse(screen.getByTestId("deviation-chart-echart").dataset.option!);
    expect(option.series.map((s: { name: string }) => s.name)).toEqual(["目标", "实际"]);
    expect(option.yAxis.axisLabel.formatter).toBe("{value}%");
    expect(option.series[0].data).toEqual([60]);
    expect(option.series[1].data).toEqual([70.59]);
    expect(option.legend).toBeDefined();
  });
});
