import { describe, it, expect, vi, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import FundResultsTable from "@/components/screening/FundResultsTable";
import { TRACKING_ERROR_NOTE } from "@/components/screening/FundScreeningForm";
import type { FundScreeningResult } from "@/lib/types";

const ROW: FundScreeningResult = {
  fundCode: "510300", fundName: "沪深300ETF", feeRate: 0.5, scale: 120.3,
  trackingIndexName: "沪深300指数", category: "宽基", trackingError1y: 0.0318,
};

function renderTable(over?: Partial<Parameters<typeof FundResultsTable>[0]>) {
  return render(
    <FundResultsTable
      results={[ROW]}
      sortBy="tracking_error_1y"
      sortDirection="ASC"
      onSort={() => {}}
      {...over}
    />,
  );
}

describe("FundResultsTable", () => {
  afterEach(() => cleanup());

  it("渲染七列表头与行数据", () => {
    renderTable();
    for (const h of ["代码", "名称", "费率(%)", "规模(亿元)", "跟踪指数", "类别", "跟踪误差(%)"]) {
      // 括号转义（列名含排序箭头，用子串匹配）
      expect(screen.getByRole("columnheader", { name: new RegExp(h.replace(/[()]/g, "\\$&")) })).toBeTruthy();
    }
    expect(screen.getByText("510300")).toBeTruthy();
    expect(screen.getByText("沪深300ETF")).toBeTruthy();
    expect(screen.getByText("沪深300指数")).toBeTruthy();
    expect(screen.getByText("宽基")).toBeTruthy();
  });

  it("TE 0.0318 展示为 3.18（×100 两位小数）", () => {
    renderTable();
    expect(screen.getByText("3.18")).toBeTruthy();
  });

  it("TE>30% 遮蔽为「—（疑似拆分/异常）」不展示假精度", () => {
    renderTable({
      results: [{ ...ROW, fundCode: "159970", fundName: "半导体ETF", trackingError1y: 0.4512 }],
    });
    expect(screen.getByText("—（疑似拆分/异常）")).toBeTruthy();
    expect(screen.queryByText("45.12")).toBeNull();
  });

  it("feeRate/scale/trackingError1y 为 null 时渲染「—」", () => {
    renderTable({
      results: [{ ...ROW, feeRate: null, scale: null, trackingError1y: null }],
    });
    expect(screen.getAllByText("—")).toHaveLength(3);
  });

  it("表头点击排序回调（费率列）", async () => {
    const onSort = vi.fn();
    renderTable({ onSort });
    await userEvent.click(screen.getByRole("columnheader", { name: /费率\(%\)/ }));
    expect(onSort).toHaveBeenCalledWith("fee_rate");
  });

  it("TE 表头 title 为收盘价口径提示", () => {
    renderTable();
    expect(screen.getByRole("columnheader", { name: /跟踪误差/ }).getAttribute("title")).toBe(TRACKING_ERROR_NOTE);
  });

  it("⭐ toggle 调用 fundCode", async () => {
    const onToggleWatchlist = vi.fn();
    renderTable({ watchlistCodes: new Set<string>(), onToggleWatchlist });
    await userEvent.click(screen.getByRole("button", { name: "加自选 510300" }));
    expect(onToggleWatchlist).toHaveBeenCalledWith("510300");
  });

  it("watchlistCodes 含基金代码时渲染实心★", () => {
    renderTable({ watchlistCodes: new Set(["510300"]), onToggleWatchlist: () => {} });
    expect(screen.getByRole("button", { name: "移除自选 510300" })).toBeTruthy();
  });
});
