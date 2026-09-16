import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import RebalanceCard from "@/components/allocation/RebalanceCard";
import type { RebalanceView } from "@/lib/types";

afterEach(cleanup);

const alertView: RebalanceView = {
  hasActivePlan: true, totalAssets: 10000, suppressed: false, anyAlert: true,
  items: [
    { assetClass: "STOCK", targetWeight: 25, actualWeight: 0, deviation: -25,
      targetAmount: 2500, currentAmount: 0, suggestedAmount: 2500, thresholdBreached: true },
    { assetClass: "CASH", targetWeight: 25, actualWeight: 100, deviation: 75,
      targetAmount: 2500, currentAmount: 10000, suggestedAmount: -7500, thresholdBreached: true },
  ],
  timeTrigger: { frequency: "OFF", anchorDate: "2026-09-16T00:00:00Z", dueDate: null, daysOverdue: 0, triggered: false },
};

describe("RebalanceCard", () => {
  it("提醒态：横幅列出触发类别，建议表买入/卖出带符号与千分位", () => {
    render(<RebalanceCard view={alertView} onAck={() => {}} />);
    expect(screen.getByTestId("rebalance-alert")).toBeTruthy();
    expect(screen.getByTestId("rebalance-alert").textContent).toContain("股票");
    expect(screen.getByTestId("rebalance-alert").textContent).toContain("-25.00pp");
    const stockRow = screen.getByTestId("rebalance-row-STOCK");
    expect(stockRow.textContent).toContain("买入");
    expect(stockRow.textContent).toContain("2,500");
    const cashRow = screen.getByTestId("rebalance-row-CASH");
    expect(cashRow.textContent).toContain("卖出");
    expect(cashRow.textContent).toContain("7,500");
  });

  it("点击 ack 触发回调；无方案空态提示；空资产提示", async () => {
    const onAck = vi.fn();
    render(<RebalanceCard view={alertView} onAck={onAck} />);
    fireEvent.click(screen.getByRole("button", { name: "已完成再平衡" }));
    await waitFor(() => expect(onAck).toHaveBeenCalled());

    cleanup();
    render(<RebalanceCard view={{ hasActivePlan: false, totalAssets: 0, suppressed: false, anyAlert: false, items: [], timeTrigger: null }} onAck={() => {}} />);
    expect(screen.getByText(/暂无生效方案/)).toBeTruthy();

    cleanup();
    render(<RebalanceCard view={{ hasActivePlan: true, totalAssets: 0, suppressed: true, anyAlert: false, items: [], timeTrigger: null }} onAck={() => {}} />);
    expect(screen.getByText(/暂无资产/)).toBeTruthy();
  });

  it("锚点日期展示（ack 后前端刷新可见）", () => {
    render(<RebalanceCard view={alertView} onAck={() => {}} />);
    expect(screen.getByTestId("rebalance-anchor").textContent).toContain("上次再平衡");
  });
});
