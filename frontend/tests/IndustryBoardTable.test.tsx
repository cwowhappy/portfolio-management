import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import IndustryBoardTable from "@/components/industry/IndustryBoardTable";
import type { IndustryBoardItem } from "@/lib/types";

// vitest globals 关闭时 RTL 自动清理不生效，须显式 cleanup（照 IndustryTable.test.tsx 惯例）
afterEach(cleanup);

const ITEMS: IndustryBoardItem[] = [
  { industryCode: "801780", industryName: "银行", pe: 5.5, pb: 0.8, roe: 12, dividendYield: 4,
    pePercentile: 40, pbPercentile: null, prosperity: "UP",
    prosperityInputs: { roeDeltaMedian: 1.0, revenueYoyMedian: 10.0, sampleSize: 42 } },
  { industryCode: "801010", industryName: "农林牧渔", pe: 20, pb: 2, roe: null, dividendYield: null,
    pePercentile: null, pbPercentile: null, prosperity: null, prosperityInputs: null },
];

describe("IndustryBoardTable", () => {
  it("渲染分位列与景气列，null 显示「—」", () => {
    render(<IndustryBoardTable items={ITEMS} />);
    expect(screen.getByText("PE 5y分位")).toBeTruthy();
    expect(screen.getByText("景气")).toBeTruthy();
    expect(screen.getByText("40.0%")).toBeTruthy();       // 银行 PE 分位
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(3); // 农林牧渔 分位×2 + 景气
    expect(screen.getByText("↑")).toBeTruthy();           // 银行 上行
  });

  it("景气徽标 title 携带可解释输入", () => {
    render(<IndustryBoardTable items={ITEMS} />);
    expect(screen.getByTitle(/ROEΔ中位数 1.*样本 42/)).toBeTruthy();
  });

  it("分位 tooltip 标注回溯口径", () => {
    render(<IndustryBoardTable items={ITEMS} />);
    // PE/PB 两个分位表头都携带同一口径 title
    expect(screen.getAllByTitle(/当前申万 2021 分类成分回溯重算/).length).toBe(2);
  });

  it("点击表头按分位本地排序", () => {
    render(<IndustryBoardTable items={ITEMS} />);
    fireEvent.click(screen.getByText(/PE 5y分位/));
    const rows = screen.getAllByRole("row").slice(1);
    expect(rows[0].textContent).toContain("银行"); // null 排最后（照 IndustryTable null→0 的降序习惯：有值在前）
  });

  it("行点击触发 onSelect", () => {
    const onSelect = vi.fn();
    render(<IndustryBoardTable items={ITEMS} onSelect={onSelect} />);
    fireEvent.click(screen.getByText("银行"));
    expect(onSelect).toHaveBeenCalledWith("801780");
  });
});
