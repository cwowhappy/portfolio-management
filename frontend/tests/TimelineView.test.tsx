import { render, screen } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import TimelineView from "@/components/journal/TimelineView";

describe("TimelineView", () => {
  it("无事件时显示空态提示", () => {
    render(<TimelineView events={[]} />);
    expect(screen.getByText(/暂无事件/)).toBeTruthy();
  });

  it("渲染事件类型与标题", () => {
    render(<TimelineView events={[{
      type: "BUY", date: "2026-08-01", title: "贵州茅台", description: "买入 100 股",
      stockCode: "600519", stockName: "贵州茅台", refId: 10, refType: "TRADE",
    }]} />);
    expect(screen.getByText("贵州茅台")).toBeTruthy();
    expect(screen.getByText("买入 100 股")).toBeTruthy();
    expect(screen.getByText("买入")).toBeTruthy();
  });
});
