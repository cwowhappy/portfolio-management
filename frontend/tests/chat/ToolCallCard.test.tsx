import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import ToolCallCard from "@/components/chat/ToolCallCard";

afterEach(() => cleanup());

describe("ToolCallCard MS-12 标签", () => {
  it.each([
    ["screen_stocks", "筛选选股"],
    ["analyze_portfolio", "持仓分析"],
    ["suggest_allocation", "配置建议"],
    ["analyze_financials", "财报解读"],
    ["analyze_industry", "行业分析"],
  ])("%s 显示中文标签 %s", (tool, label) => {
    render(<ToolCallCard toolCallId="tc1" toolName={tool} status="executing" />);
    expect(screen.getByText(label)).toBeTruthy();
  });
});
