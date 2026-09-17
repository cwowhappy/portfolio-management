import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import IndustryStockTable from "@/components/industry/IndustryStockTable";
import type { IndustryStock } from "@/lib/types";

// vitest globals 关闭时 RTL 自动清理不生效，须显式 cleanup（照 IndustryBoardTable.test.tsx 惯例）
afterEach(cleanup);

const mk = (code: string, mv: number, prosperity: IndustryStock["prosperity"] = null): IndustryStock => ({
  stockCode: code, stockName: "股" + code, totalMv: mv * 1e8, revenue: mv * 1e7,
  revenueReportDate: "2025-12-31", roe: mv, peTtm: mv, pb: mv, dividendYield: mv, prosperity,
});

describe("IndustryStockTable", () => {
  it("渲染排名/亿元格式化/报告期角标/景气徽标", () => {
    render(<IndustryStockTable stocks={[mk("601398", 20000, "UP")]} sortBy="total_mv" sortDirection="DESC" onSort={vi.fn()} />);
    expect(screen.getByText("1")).toBeTruthy();          // 排名
    expect(screen.getByText("20000.0")).toBeTruthy();    // 总市值亿
    expect(screen.getByText(/2025-12-31/)).toBeTruthy(); // 报告期
    expect(screen.getByText("↑")).toBeTruthy();
  });

  it("55 行分页 50/页，翻页可用", () => {
    const stocks = Array.from({ length: 55 }, (_, i) => mk(String(600000 + i), 10000 - i));
    render(<IndustryStockTable stocks={stocks} sortBy="total_mv" sortDirection="DESC" onSort={vi.fn()} />);
    expect(screen.getByText(/第 1\/2 页/)).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(screen.getByText(/第 2\/2 页/)).toBeTruthy();
  });

  it("点击市值表头回调排序键", () => {
    const onSort = vi.fn();
    render(<IndustryStockTable stocks={[mk("601398", 1)]} sortBy="total_mv" sortDirection="DESC" onSort={onSort} />);
    fireEvent.click(screen.getByText(/总市值/));
    expect(onSort).toHaveBeenCalledWith("total_mv");
  });
});
