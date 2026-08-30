import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import PortfolioBoard from "@/components/portfolio/PortfolioBoard";
import * as portfolioApi from "@/lib/portfolioApi";
import type { GroupView, PositionView } from "@/lib/types";

vi.mock("@/lib/portfolioApi", () => ({
  fetchOverview: vi.fn(),
  fetchGroups: vi.fn(),
  fetchPositions: vi.fn(),
  fetchAllocation: vi.fn(),
  fetchIndustryDistribution: vi.fn(),
  fetchConcentration: vi.fn(),
  buy: vi.fn(),
  sell: vi.fn(),
  createGroup: vi.fn(),
  renameGroup: vi.fn(),
  addCashTransaction: vi.fn(),
  addCashDividend: vi.fn(),
  addStockDividend: vi.fn(),
  editTrade: vi.fn(),
  fetchTrades: vi.fn(),
  deletePosition: vi.fn(),
}));

const api = vi.mocked(portfolioApi);

const groups: GroupView[] = [
  { id: 1, name: "华泰", type: "ACCOUNT", positionCount: 1, cashBalance: 100 },
  { id: 2, name: "中信", type: "ACCOUNT", positionCount: 1, cashBalance: 200 },
];

const positions: PositionView[] = [
  {
    id: 1, groupId: 1, stockCode: "600519", stockName: "贵州茅台",
    quantity: 100, avgCost: 1500, price: 1600, marketValue: 160000,
    floatingPnl: 10000, pnlRatio: 6.67, realizedPnl: 0, totalBuyCost: 150000, cumulativeCashDividend: 0,
  },
  {
    id: 2, groupId: 2, stockCode: "600036", stockName: "招商银行",
    quantity: 200, avgCost: 30, price: 35, marketValue: 7000,
    floatingPnl: 1000, pnlRatio: 16.67, realizedPnl: 0, totalBuyCost: 6000, cumulativeCashDividend: 0,
  },
];

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchOverview.mockResolvedValue({
    totalAssets: 167000, totalCost: 156000, totalPnl: 11000, todayPnl: 0,
    cashTotal: 300, totalCashDividend: 0, positionCount: 2, groupCount: 2,
  });
  api.fetchGroups.mockResolvedValue(groups);
  api.fetchPositions.mockResolvedValue(positions);
  api.fetchAllocation.mockResolvedValue({ slices: [] });
  api.fetchIndustryDistribution.mockResolvedValue({ slices: [] });
  api.fetchConcentration.mockResolvedValue({ holdings: [], top5Ratio: 0 });
  api.sell.mockResolvedValue(positions[0]);
});

afterEach(() => {
  cleanup();
});

describe("PortfolioBoard", () => {
  it("渲染标题与分组", async () => {
    render(<PortfolioBoard />);
    expect(await screen.findByText("持仓组合")).toBeTruthy();
    const tabs = await screen.findByTestId("group-tabs");
    expect(within(tabs).getByText("华泰")).toBeTruthy();
  });

  it("加载失败时显示错误文案", async () => {
    api.fetchOverview.mockRejectedValue(new Error("后端不可用"));
    render(<PortfolioBoard />);
    expect(await screen.findByText("加载失败：后端不可用")).toBeTruthy();
  });

  it("非 Error 异常回退为默认错误文案", async () => {
    api.fetchOverview.mockRejectedValue("boom");
    render(<PortfolioBoard />);
    expect(await screen.findByText("加载失败：加载失败")).toBeTruthy();
  });

  it("切换分组过滤持仓列表", async () => {
    render(<PortfolioBoard />);
    await screen.findByText("贵州茅台");
    const table = screen.getByTestId("position-table");
    expect(within(table).getByText("招商银行")).toBeTruthy();

    const tabs = screen.getByTestId("group-tabs");
    fireEvent.click(within(tabs).getByText("华泰"));
    expect(within(table).getByText("贵州茅台")).toBeTruthy();
    expect(within(table).queryByText("招商银行")).toBeNull();

    fireEvent.click(within(tabs).getByText("全部"));
    expect(within(table).getByText("招商银行")).toBeTruthy();
  });

  it("子组件 onChanged 触发重新加载", async () => {
    render(<PortfolioBoard />);
    await screen.findByText("贵州茅台");
    expect(api.fetchOverview).toHaveBeenCalledTimes(1);

    fireEvent.change(screen.getAllByLabelText("卖价")[0], { target: { value: "1600" } });
    fireEvent.change(screen.getAllByLabelText("卖量")[0], { target: { value: "50" } });
    fireEvent.click(screen.getAllByRole("button", { name: "卖出" })[0]);
    await vi.waitFor(() => expect(api.fetchOverview).toHaveBeenCalledTimes(2));
    expect(api.sell).toHaveBeenCalledWith(expect.objectContaining({ positionId: 1, price: 1600, quantity: 50 }));
  });
});
