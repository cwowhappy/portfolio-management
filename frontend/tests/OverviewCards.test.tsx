import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import OverviewCards from "@/components/portfolio/OverviewCards";

describe("OverviewCards", () => {
  it("渲染四项数值", () => {
    render(<OverviewCards overview={{ totalAssets: 12000, totalCost: 10000, totalPnl: 2000, todayPnl: 150, cashTotal: 0, totalCashDividend: 300, positionCount: 1, groupCount: 1 }} />);
    expect(screen.getByText("总资产")).toBeTruthy();
    expect(screen.getByText("12,000.00")).toBeTruthy();
    expect(screen.getByText("2,000.00")).toBeTruthy();
    expect(screen.getByText("累计分红")).toBeTruthy();
    expect(screen.getByText("300.00")).toBeTruthy();
  });
});
