import { describe, it, expect, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import ValuationHeatmap, { rank } from "@/components/industry/ValuationHeatmap";

describe("rank", () => {
  it("中位数返回 0.5", () => {
    expect(rank([10, 20, 30], 20)).toBeCloseTo(0.5);
  });
  it("null 返回 null", () => {
    expect(rank([10, 20], null)).toBeNull();
  });
});

describe("ValuationHeatmap", () => {
  afterEach(() => cleanup());

  it("渲染行业与三列", () => {
    render(<ValuationHeatmap industries={[
      { industryCode: "801780", industryName: "银行", pe: 5.9, pb: 0.65, roe: 11, dividendYield: 5.1 },
      { industryCode: "801080", industryName: "电子", pe: 35.6, pb: 3.4, roe: 12.3, dividendYield: 0.8 },
    ]} />);
    expect(screen.getByText("估值热力图")).toBeTruthy();
    expect(screen.getByText("银行")).toBeTruthy();
    expect(screen.getByText("电子")).toBeTruthy();
  });
});
