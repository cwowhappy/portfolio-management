import { describe, it, expect, vi, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ScreeningResultsTable from "@/components/screening/ScreeningResultsTable";

const ROW = {
  stockCode: "601398", stockName: "工商银行", industryCode: "801780", industryName: "银行",
  peTtm: 5.6, pb: 0.62, dividendYield: 5.4, roe: 11.8, roa: 0.95, grossMargin: 0,
  debtToAssets: 91.8, currentRatio: 0.9, revenueYoy: 2.1, netprofitYoy: 1.8,
  totalMv: 2200000000000, turnoverRate: 0.18,
};

describe("ScreeningResultsTable", () => {
  afterEach(() => cleanup());

  it("渲染结果并触发排序", async () => {
    const onSort = vi.fn();
    render(<ScreeningResultsTable results={[ROW]} sortBy="pe_ttm" sortDirection="ASC" onSort={onSort} />);
    expect(screen.getByText("工商银行")).toBeTruthy();
    await userEvent.click(screen.getByText(/PE-TTM/));
    expect(onSort).toHaveBeenCalledWith("pe_ttm");
  });
});
