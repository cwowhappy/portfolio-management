import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import DeviationChart from "@/components/allocation/DeviationChart";

describe("DeviationChart", () => {
  it("无生效方案时显示空态提示", () => {
    render(<DeviationChart deviation={{ slices: [] }} />);
    expect(screen.getByText(/暂无生效方案/)).toBeTruthy();
  });

  it("渲染偏离度摘要", () => {
    render(<DeviationChart deviation={{ slices: [{ assetClass: "STOCK", targetWeight: 60, actualWeight: 70.59, deviation: 10.59 }] }} />);
    expect(screen.getByText(/股票 偏离 \+10.59%/)).toBeTruthy();
  });
});
