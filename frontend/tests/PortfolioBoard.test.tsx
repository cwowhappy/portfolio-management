import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import PortfolioBoard from "@/components/portfolio/PortfolioBoard";
import * as portfolioApi from "@/lib/portfolioApi";
import type { GroupView, PortfolioOverview, PositionView } from "@/lib/types";

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

  it("reload 竞态：丢弃过期响应，只保留最新一次加载结果", async () => {
    let resolveStale!: (v: PortfolioOverview) => void;
    const staleGate = new Promise<PortfolioOverview>((res) => { resolveStale = res; });
    const staleData = { totalAssets: 111111, totalCost: 0, totalPnl: 0, todayPnl: 0, cashTotal: 0, totalCashDividend: 0, positionCount: 0, groupCount: 0 };
    const freshData = { totalAssets: 999999, totalCost: 0, totalPnl: 0, todayPnl: 0, cashTotal: 0, totalCashDividend: 0, positionCount: 0, groupCount: 0 };

    // 第 1 次（挂载）正常返回；第 2 次（旧 reload）挂起；第 3 次（新 reload）立即返回 fresh
    api.fetchOverview.mockResolvedValueOnce({ totalAssets: 500, totalCost: 0, totalPnl: 0, todayPnl: 0, cashTotal: 0, totalCashDividend: 0, positionCount: 0, groupCount: 0 });
    api.fetchOverview.mockReturnValueOnce(staleGate);
    api.fetchOverview.mockResolvedValueOnce(freshData);

    render(<PortfolioBoard />);
    await screen.findByText("500.00");
    expect(api.fetchOverview).toHaveBeenCalledTimes(1);

    // 触发 reload #2（挂起中）
    const nameInput = screen.getByPlaceholderText("分组名（如 华泰）");
    fireEvent.change(nameInput, { target: { value: "组A" } });
    fireEvent.click(screen.getByRole("button", { name: "新建" }));
    await vi.waitFor(() => expect(api.fetchOverview).toHaveBeenCalledTimes(2));

    // 触发 reload #3（立即返回 fresh），应覆盖 UI
    fireEvent.change(nameInput, { target: { value: "组B" } });
    fireEvent.click(screen.getByRole("button", { name: "新建" }));
    await vi.waitFor(() => expect(api.fetchOverview).toHaveBeenCalledTimes(3));
    expect(await screen.findByText("999,999.00")).toBeTruthy();

    // 放行旧 reload 的过期响应：守卫应丢弃，UI 不被覆盖成 111,111.00
    await act(async () => { resolveStale(staleData); });
    await vi.waitFor(() => expect(api.fetchOverview).toHaveBeenCalledTimes(3));
    expect(screen.queryByText("111,111.00")).toBeNull();
    expect(screen.getByText("999,999.00")).toBeTruthy();
  });
});
