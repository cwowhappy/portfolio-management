import { afterEach, describe, it, expect } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import { DataTable } from "@/components/chat/charts/DataTable";
import type { TableSpec } from "@/lib/chart-spec";

const spec: TableSpec = {
  specVersion: 1, type: "table", title: "600519 贵州茅台 财务指标",
  columns: [
    { key: "reportDate", label: "报告期" },
    { key: "eps", label: "每股收益EPS", align: "right" },
    { key: "revenueYi", label: "营收(亿元)", align: "right", sortable: false },
  ],
  rows: [
    { reportDate: "2026-06-30", eps: 24.2, revenueYi: 900.0 },
    { reportDate: "2025-12-31", eps: 68.6, revenueYi: 1741.0 },
    { reportDate: "2026-03-31", eps: 11.8, revenueYi: 414.0 },
  ],
};

afterEach(() => cleanup());

function cells(): string[] {
  return [...document.querySelectorAll("tbody tr td:first-child")].map((td) => td.textContent);
}

describe("DataTable（TanStack v9 + sticky 表头）", () => {
  it("渲染列头与行（label 与 rows 键映射）", () => {
    render(<DataTable spec={spec} />);
    expect(screen.getByText("报告期")).toBeTruthy();
    expect(screen.getByText("每股收益EPS")).toBeTruthy();
    expect(cells()).toEqual(["2026-06-30", "2025-12-31", "2026-03-31"]);
  });

  it("点击可排序列：升序/降序切换（numeric collation）", () => {
    render(<DataTable spec={spec} />);
    fireEvent.click(screen.getByText("每股收益EPS"));
    // brief 原文此处写 ["2026-03-31","2025-12-31","2026-06-30"]，对应 eps 11.8/68.6/24.2，非升序；
    // 按 brief 注释语义（11.8 < 24.2 < 68.6）修正为真实升序，严格度不变（下行降序断言未动）。
    expect(cells()).toEqual(["2026-03-31", "2026-06-30", "2025-12-31"]); // eps 升序 11.8 < 24.2 < 68.6
    fireEvent.click(screen.getByText("每股收益EPS"));
    expect(cells()).toEqual(["2025-12-31", "2026-06-30", "2026-03-31"]); // 降序
  });

  it("sortable:false 列点击不排序", () => {
    render(<DataTable spec={spec} />);
    fireEvent.click(screen.getByText("营收(亿元)"));
    expect(cells()).toEqual(["2026-06-30", "2025-12-31", "2026-03-31"]); // 原序
  });

  it("表头 sticky（CSS 类/样式）且滚动容器限高", () => {
    render(<DataTable spec={spec} />);
    const th = screen.getByText("报告期").closest("th")!;
    expect(th.className).toContain("sticky");
    expect(document.querySelector(".max-h-\\[360px\\]")).toBeTruthy();
  });

  it("行值为 null 渲染为空单元格（不崩）", () => {
    render(<DataTable spec={{ ...spec, rows: [{ reportDate: "2026-06-30", eps: null }] }} />);
    expect(cells()).toEqual(["2026-06-30"]);
  });
});
