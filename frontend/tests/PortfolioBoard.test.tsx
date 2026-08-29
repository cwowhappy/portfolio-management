import { describe, it, expect, vi } from "vitest";
import { render, screen, within } from "@testing-library/react";
import PortfolioBoard from "@/components/portfolio/PortfolioBoard";

vi.mock("@/lib/portfolioApi", () => ({
  fetchOverview: vi.fn().mockResolvedValue({ totalAssets: 0, totalCost: 0, totalPnl: 0, todayPnl: 0, cashTotal: 0, positionCount: 0, groupCount: 1 }),
  fetchGroups: vi.fn().mockResolvedValue([{ id: 1, name: "华泰", type: "ACCOUNT", positionCount: 0, cashBalance: 0 }]),
  fetchPositions: vi.fn().mockResolvedValue([]),
  fetchAllocation: vi.fn().mockResolvedValue({ slices: [] }),
  fetchIndustryDistribution: vi.fn().mockResolvedValue({ slices: [] }),
  fetchConcentration: vi.fn().mockResolvedValue({ holdings: [], top5Ratio: 0 }),
  buy: vi.fn(),
  sell: vi.fn(),
  createGroup: vi.fn(),
  deletePosition: vi.fn(),
}));

describe("PortfolioBoard", () => {
  it("渲染标题与分组", async () => {
    render(<PortfolioBoard />);
    expect(await screen.findByText("持仓组合")).toBeTruthy();
    const tabs = await screen.findByTestId("group-tabs");
    expect(within(tabs).getByText("华泰")).toBeTruthy();
  });
});
