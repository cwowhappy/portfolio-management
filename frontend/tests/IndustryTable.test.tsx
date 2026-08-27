import { describe, it, expect, afterEach } from "vitest";
import { cleanup, render, screen, fireEvent, within } from "@testing-library/react";
import IndustryTable from "@/components/valuation/IndustryTable";
import type { IndustryValuation } from "@/lib/types";

const industries: IndustryValuation[] = [
  { industryCode: "01", industryName: "银行", pe: 5, pb: 0.6, roe: 12, dividendYield: 5.5 },
  { industryCode: "02", industryName: "医药", pe: 30, pb: 4, roe: 8, dividendYield: 1.0 },
  { industryCode: "03", industryName: "证券", pe: 18, pb: 1.5, roe: null, dividendYield: null },
];

function industryRows(): (string | null)[] {
  const table = screen.getByRole("table");
  return within(table)
    .getAllByRole("row")
    .slice(1)
    .map((r) => within(r).getAllByRole("cell")[0].textContent);
}

describe("IndustryTable", () => {
  afterEach(() => {
    cleanup();
  });

  it("默认按 PE 升序渲染，null 显示为 —", () => {
    render(<IndustryTable industries={industries} />);
    expect(industryRows()).toEqual(["银行", "证券", "医药"]);
    // 证券的 roe 与 dividendYield 为 null → 显示 —
    expect(screen.getAllByText("—").length).toBe(2);
  });

  it("点击表头切换排序", () => {
    render(<IndustryTable industries={industries} />);
    // 股息率升序：证券(null→0) < 医药(1.0) < 银行(5.5)
    fireEvent.click(screen.getByText(/股息率/));
    expect(industryRows()).toEqual(["证券", "医药", "银行"]);
    // PB 升序：银行(0.6) < 证券(1.5) < 医药(4.0)
    fireEvent.click(screen.getByText(/PB/));
    expect(industryRows()).toEqual(["银行", "证券", "医药"]);
  });
});
