import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import PositionTable from "@/components/portfolio/PositionTable";

describe("PositionTable", () => {
  it("渲染持仓行", () => {
    render(<PositionTable positions={[{
      id: 1, groupId: 1, stockCode: "600519", stockName: "贵州茅台",
      quantity: 100, avgCost: 1500, price: 1600, marketValue: 160000,
      floatingPnl: 10000, pnlRatio: 6.67, realizedPnl: 0, totalBuyCost: 150000, cumulativeCashDividend: 0,
    }]} />);
    expect(screen.getByText("贵州茅台")).toBeTruthy();
    expect(screen.getByText("600519")).toBeTruthy();
  });
});
