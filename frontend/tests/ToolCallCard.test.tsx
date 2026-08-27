import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import ToolCallCard from "@/components/chat/ToolCallCard";

afterEach(cleanup);

const baseProps = {
  toolCallId: "c1",
  toolName: "get_quote",
};

describe("ToolCallCard", () => {
  it("显示工具中文名与参数摘要", () => {
    render(
      <ToolCallCard {...baseProps} parameters={{ code: "600519", limit: 60 }} status="complete" />,
    );
    expect(screen.getByText("实时行情")).toBeTruthy();
    expect(screen.getByText("600519 · 近60根")).toBeTruthy();
  });

  it("未知工具名原样显示", () => {
    render(<ToolCallCard {...baseProps} toolName="custom_tool" status="complete" />);
    expect(screen.getByText("custom_tool")).toBeTruthy();
  });

  it("get_valuation 显示中文标签", () => {
    render(<ToolCallCard toolCallId="c1" toolName="get_valuation" parameters={{}} status="complete" />);
    expect(screen.getByText("估值查询")).toBeTruthy();
    expect(screen.getByText("查询市场估值")).toBeTruthy();
  });

  it("参数为 JSON 字符串时解析出摘要", () => {
    render(
      <ToolCallCard
        {...baseProps}
        toolName="get_kline"
        parameters={'{"code":"600519","period":"week"}'}
        status="complete"
      />,
    );
    expect(screen.getByText("600519 · week")).toBeTruthy();
  });

  it("参数为损坏的字符串时截断显示原文", () => {
    render(
      <ToolCallCard {...baseProps} parameters={"{broken" + "x".repeat(70)} status="complete" />,
    );
    expect(screen.getByText("{broken" + "x".repeat(53))).toBeTruthy();
  });

  it("参数缺失时无摘要", () => {
    render(<ToolCallCard {...baseProps} status="complete" />);
    expect(screen.queryByText("600519")).toBeNull();
  });

  it("运行中显示脉冲动画", () => {
    const { container } = render(<ToolCallCard {...baseProps} status="inProgress" />);
    expect(container.querySelector(".tool-pulse")).toBeTruthy();
    expect(container.querySelector(".tool-card.running")).toBeTruthy();
  });

  it("executing 状态同样视为运行中", () => {
    const { container } = render(<ToolCallCard {...baseProps} status="executing" />);
    expect(container.querySelector(".tool-pulse")).toBeTruthy();
  });

  it("完成态显示对勾", () => {
    render(<ToolCallCard {...baseProps} result='{"pe":19.95}' status="complete" />);
    expect(screen.getByText("✓")).toBeTruthy();
  });

  it("isError 时显示感叹号", () => {
    render(<ToolCallCard {...baseProps} isError result='{"error":"x"}' status="complete" />);
    expect(screen.getByText("!")).toBeTruthy();
  });

  it("结果含 error 时视为失败", () => {
    render(<ToolCallCard {...baseProps} result="error: 上游不可用" status="complete" />);
    expect(screen.getByText("!")).toBeTruthy();
  });

  it("点击展开/收起结果详情", () => {
    render(<ToolCallCard {...baseProps} result='{"pe":19.95}' status="complete" />);
    expect(screen.queryByText('{"pe":19.95}')).toBeNull();
    fireEvent.click(screen.getByRole("button"));
    expect(screen.getByText('{"pe":19.95}')).toBeTruthy();
    fireEvent.click(screen.getByRole("button"));
    expect(screen.queryByText('{"pe":19.95}')).toBeNull();
  });

  it("对象结果序列化为 JSON 文本", () => {
    render(<ToolCallCard {...baseProps} result={{ pe: 19.95 }} status="complete" />);
    fireEvent.click(screen.getByRole("button"));
    expect(screen.getByText('{"pe":19.95}')).toBeTruthy();
  });

  it("完成但无结果时显示“无结果”", () => {
    render(<ToolCallCard {...baseProps} status="complete" />);
    fireEvent.click(screen.getByRole("button"));
    expect(screen.getByText("无结果")).toBeTruthy();
  });

  it("运行中且无参数时显示“执行中…”", () => {
    render(<ToolCallCard {...baseProps} status="inProgress" />);
    fireEvent.click(screen.getByRole("button"));
    expect(screen.getByText("执行中…")).toBeTruthy();
  });

  it("运行中且有参数时摘要兜底显示", () => {
    const { container } = render(
      <ToolCallCard {...baseProps} parameters={{ code: "600519" }} status="inProgress" />,
    );
    fireEvent.click(screen.getByRole("button"));
    expect(container.querySelector("pre")?.textContent).toBe("600519");
  });

  it("超长结果截断到 2000 字符", () => {
    const long = "x".repeat(2500);
    render(<ToolCallCard {...baseProps} result={long} status="complete" />);
    fireEvent.click(screen.getByRole("button"));
    const text = screen.getByText(/x+/);
    expect(text.textContent?.length).toBe(2001); // 2000 字符 + 省略号
    expect(text.textContent?.endsWith("…")).toBe(true);
  });

  it("恰好 2000 字符不截断", () => {
    const exact = "x".repeat(2000);
    render(<ToolCallCard {...baseProps} result={exact} status="complete" />);
    fireEvent.click(screen.getByRole("button"));
    expect(screen.getByText(/x+/).textContent).toBe(exact);
  });
});
