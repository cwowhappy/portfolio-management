import { afterEach, describe, it, expect } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import PositionTable from "@/components/portfolio/PositionTable";
import type { GroupView, PositionView } from "@/lib/types";

const basePosition: PositionView = {
  id: 1, groupId: 1, stockCode: "600519", stockName: "贵州茅台",
  quantity: 100, avgCost: 1500, price: 1600, marketValue: 160000,
  floatingPnl: 10000, pnlRatio: 6.67, realizedPnl: 0, totalBuyCost: 150000, cumulativeCashDividend: 0,
};

afterEach(() => {
  cleanup();
});

describe("PositionTable", () => {
  it("渲染持仓行", () => {
    render(<PositionTable positions={[basePosition]} />);
    expect(screen.getByText("贵州茅台")).toBeTruthy();
    expect(screen.getByText("600519")).toBeTruthy();
  });

  it("数值字段为 null 时显示占位符", () => {
    render(<PositionTable
      positions={[{
        ...basePosition,
        avgCost: null, price: null, marketValue: null, floatingPnl: null, pnlRatio: null,
      }]}
      groups={[{ id: 1, name: "华泰", type: "ACCOUNT", positionCount: 1, cashBalance: 0 }]}
    />);
    const row = screen.getByText("贵州茅台").closest("tr")!;
    // 成本价/现价/市值/盈亏/收益率 五列均为 —
    expect(within(row).getAllByText("—")).toHaveLength(5);
  });

  it("pnlRatio 有值时渲染百分比", () => {
    render(<PositionTable positions={[basePosition]} />);
    expect(screen.getByText("6.67%")).toBeTruthy();
  });

  it("分组缺失或未传 groups 时所属分组回退为占位符", () => {
    render(<PositionTable
      positions={[basePosition, { ...basePosition, id: 2, groupId: 99, stockCode: "600036", stockName: "招商银行" }]}
      groups={[{ id: 1, name: "华泰", type: "ACCOUNT", positionCount: 1, cashBalance: 0 }]}
    />);
    const knownRow = screen.getByText("贵州茅台").closest("tr")!;
    expect(within(knownRow).getByText("华泰")).toBeTruthy();
    const unknownRow = screen.getByText("招商银行").closest("tr")!;
    expect(within(unknownRow).getByText("—")).toBeTruthy();
  });

  it("不传 groups 时所属分组列显示占位符", () => {
    render(<PositionTable positions={[basePosition]} />);
    const row = screen.getByText("贵州茅台").closest("tr")!;
    expect(within(row).getByText("—")).toBeTruthy();
  });

  it("空持仓显示空态提示", () => {
    const groups: GroupView[] = [{ id: 1, name: "华泰", type: "ACCOUNT", positionCount: 0, cashBalance: 0 }];
    render(<PositionTable positions={[]} groups={groups} />);
    expect(screen.getByText("暂无持仓，点击「买入」开始记录")).toBeTruthy();
  });
});
