import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import IndustryDrilldown from "@/components/industry/IndustryDrilldown";
import { useAuth } from "@/lib/auth";
import type { IndustryBoardItem, IndustryStock } from "@/lib/types";

// vi.hoisted：vi.mock 工厂被提升到静态 import 前，mock 引用须同层提升（仓库既有教训）。
const { fetchIndustryBoardMock, fetchIndustryStocksMock } = vi.hoisted(() => ({
  fetchIndustryBoardMock: vi.fn(),
  fetchIndustryStocksMock: vi.fn(),
}));

vi.mock("@/lib/industryApi", () => ({
  fetchIndustryBoard: fetchIndustryBoardMock,
  fetchIndustryStocks: fetchIndustryStocksMock,
}));

// 组件新增 useAuth()（保存研究结论入口），本测试无 AuthProvider，照 ScreenerBoard 惯例 mock。
vi.mock("@/lib/auth", () => ({ useAuth: vi.fn() }));

// vitest globals 关闭时 RTL 自动清理不生效，须显式 cleanup（照 IndustryStockTable.test.tsx 惯例）
afterEach(() => {
  cleanup();
  fetchIndustryBoardMock.mockReset();
  fetchIndustryStocksMock.mockReset();
});

const boardRow: IndustryBoardItem = {
  industryCode: "801780", industryName: "银行", pe: 5.5, pb: 0.8, roe: 12, dividendYield: 4,
  pePercentile: 40, pbPercentile: 30, prosperity: "UP",
  prosperityInputs: { roeDeltaMedian: 1, revenueYoyMedian: 10, sampleSize: 42 },
};

const stock = (code: string, name = "股" + code): IndustryStock => ({
  stockCode: code, stockName: name, totalMv: 2e12, revenue: 4e11,
  revenueReportDate: "2025-12-31", roe: 11, peTtm: 6, pb: 0.6, dividendYield: 5, prosperity: null,
});

describe("IndustryDrilldown", () => {
  beforeEach(() => {
    vi.mocked(useAuth).mockReturnValue({ user: null, loading: false } as ReturnType<typeof useAuth>);
    fetchIndustryBoardMock.mockResolvedValue([boardRow]);
    fetchIndustryStocksMock.mockResolvedValue([stock("601398", "工商银行")]);
  });

  it("页头含行业名/成员数/景气，成员表带报告期角标", async () => {
    render(<IndustryDrilldown industryCode="801780" />);
    expect(await screen.findByText("银行")).toBeTruthy();
    expect(screen.getByText(/成员 1/)).toBeTruthy();
    expect(screen.getByText("↑")).toBeTruthy();          // 页头行业景气
    expect(screen.getByText(/2025-12-31/)).toBeTruthy(); // 营收报告期角标
  });

  it("排序切换触发重新拉取", async () => {
    render(<IndustryDrilldown industryCode="801780" />);
    await screen.findByText("工商银行");
    fireEvent.click(screen.getByText(/总市值/));
    await waitFor(() =>
      expect(fetchIndustryStocksMock).toHaveBeenLastCalledWith("801780",
        expect.objectContaining({ sortBy: "total_mv", sortDirection: "ASC" })));
  });

  it("排序变更后分页归 1（T5 评审承接项：先翻第 2 页，切排序，回到第 1 页）", async () => {
    fetchIndustryStocksMock.mockResolvedValue(
      Array.from({ length: 55 }, (_, i) => stock(String(600000 + i))));
    render(<IndustryDrilldown industryCode="801780" />);
    await screen.findByText(/第 1\/2 页/);
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(screen.getByText(/第 2\/2 页/)).toBeTruthy();
    fireEvent.click(screen.getByText(/总市值/));
    await waitFor(() =>
      expect(fetchIndustryStocksMock).toHaveBeenLastCalledWith("801780",
        expect.objectContaining({ sortBy: "total_mv", sortDirection: "ASC" })));
    expect(await screen.findByText(/第 1\/2 页/)).toBeTruthy();
  });

  it("保存研究结论入口：未登录隐藏，登录后挂载并带入行业上下文", async () => {
    const { rerender } = render(<IndustryDrilldown industryCode="801780" />);
    await screen.findByText("工商银行");
    expect(screen.queryByTestId("research-note-open")).toBeNull(); // 未登录隐藏

    const userStub = { id: 1, username: "u", role: "USER", status: "APPROVED", enabled: true } as NonNullable<ReturnType<typeof useAuth>["user"]>;
    vi.mocked(useAuth).mockReturnValue({ user: userStub, loading: false } as ReturnType<typeof useAuth>);
    rerender(<IndustryDrilldown industryCode="801780" />);
    fireEvent.click(screen.getByTestId("research-note-open"));
    // 行业上下文（榜单名「银行」）预填进弹窗标题，验证 props 正确传入
    expect((screen.getByTestId("research-note-title") as HTMLInputElement).value).toBe("银行 研究结论");
  });
});
