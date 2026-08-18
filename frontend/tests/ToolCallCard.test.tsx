import { describe, expect, it } from "vitest";
import { renderToString } from "react-dom/server";
import ToolCallCard, { type ToolCallState } from "../components/chat/ToolCallCard";

describe("ToolCallCard", () => {
  it("渲染运行中卡片并显示工具名与参数摘要", () => {
    const tool: ToolCallState = {
      toolCallId: "c1",
      name: "get_quote",
      args: '{"code":"600519"}',
      result: null,
      status: "running",
    };
    const html = renderToString(<ToolCallCard tool={tool} />);
    expect(html).toContain("实时行情");
    expect(html).toContain("600519");
    expect(html).toContain("tool-card");
  });

  it("完成态卡片显示对勾状态", () => {
    const tool: ToolCallState = {
      toolCallId: "c2",
      name: "get_financials",
      args: '{"code":"600519"}',
      result: '{"pe":19.95}',
      status: "done",
    };
    const html = renderToString(<ToolCallCard tool={tool} />);
    expect(html).toContain("财务指标");
    expect(html).toContain("✓");
  });
});
