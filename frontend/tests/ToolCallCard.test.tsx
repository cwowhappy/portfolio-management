import { describe, expect, it } from "vitest";
import { renderToString } from "react-dom/server";
import ToolCallCard from "../components/chat/ToolCallCard";

describe("ToolCallCard", () => {
  it("渲染运行中卡片并显示工具名与参数摘要", () => {
    const html = renderToString(
      <ToolCallCard
        toolCallId="c1"
        toolName="get_quote"
        argsText='{"code":"600519"}'
        status="inProgress"
      />,
    );
    expect(html).toContain("实时行情");
    expect(html).toContain("600519");
    expect(html).toContain("tool-card");
  });

  it("完成态卡片显示对勾状态", () => {
    const html = renderToString(
      <ToolCallCard
        toolCallId="c2"
        toolName="get_financials"
        argsText='{"code":"600519"}'
        result='{"pe":19.95}'
        status="complete"
      />,
    );
    expect(html).toContain("财务指标");
    expect(html).toContain("✓");
  });

  it("错误态显示感叹号标记", () => {
    const html = renderToString(
      <ToolCallCard
        toolCallId="c3"
        toolName="get_kline"
        argsText='{"code":"600519"}'
        isError
        status="complete"
        result='{"error":"..."}'
      />,
    );
    expect(html).toContain("!");
  });
});
