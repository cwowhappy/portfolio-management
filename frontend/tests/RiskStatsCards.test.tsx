import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import RiskStatsCards from "@/components/analytics/RiskStatsCards";
import type { RiskStatsView } from "@/lib/types";

// 后端契约：数值 toPlainString 字符串（setScale(10)，如 "0.2500000000"），null=「—」；
// recoveryDate null=回撤进行中。
const stats: RiskStatsView = {
  mdd: "0.2500000000", currentDrawdown: "0.0000000000", peakDate: "2026-01-06", troughDate: "2026-01-07",
  recoveryDate: null, drawdownDays: 1, sharpe: "2.5138421875", sharpeRfFallback: false,
  calmar: "1.5000000000", windowDays: 90,
};

describe("RiskStatsCards", () => {
  it("渲染四张指标卡：百分比/数值格式化 + recoveryDate null → 「—」与进行中标注", () => {
    render(<RiskStatsCards stats={stats} />);
    expect(screen.getByTestId("risk-stats")).toBeTruthy();
    expect(screen.getByText("最大回撤")).toBeTruthy();
    expect(screen.getByText("25.00%")).toBeTruthy(); // 0.25 → 百分比
    expect(screen.getByText("—")).toBeTruthy(); // recoveryDate null → —
    expect(screen.getByText(/进行中/)).toBeTruthy();
  });

  it("sharpe/calmar/mdd 为 null 时显示「—」", () => {
    render(<RiskStatsCards stats={{ ...stats, sharpe: null, calmar: null, mdd: null }} />);
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(3);
  });
});
