import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import InterruptApprovalCard from "@/components/chat/InterruptApprovalCard";

afterEach(cleanup);

describe("InterruptApprovalCard", () => {
  it("渲染工具名与折叠入参 JSON", () => {
    render(
      <InterruptApprovalCard
        toolName="write_note"
        toolInput={{ file: "a.md", content: "内容" }}
        onApprove={() => {}}
        onDeny={() => {}}
      />,
    );
    expect(screen.getByText(/write_note/)).toBeTruthy();
    fireEvent.click(screen.getByText("查看调用参数"));
    expect(screen.getByText(/"file"/)).toBeTruthy();
  });

  it("入参为 JSON 字符串时原样展示（metadata.toolInput 实际形态）", () => {
    render(
      <InterruptApprovalCard
        toolName="write_note"
        toolInput={'{"file":"a.md"}'}
        onApprove={() => {}}
        onDeny={() => {}}
      />,
    );
    fireEvent.click(screen.getByText("查看调用参数"));
    expect(screen.getByText(/"file":"a.md"/)).toBeTruthy();
  });

  it("metadata 缺失时以 message 兜底且不渲染参数区", () => {
    render(
      <InterruptApprovalCard
        toolName="未知工具"
        message="该操作需要确认"
        onApprove={() => {}}
        onDeny={() => {}}
      />,
    );
    expect(screen.getByText("该操作需要确认")).toBeTruthy();
    expect(screen.queryByText("查看调用参数")).toBeNull();
  });

  it("批准/拒绝按钮触发回调", () => {
    const onApprove = vi.fn();
    const onDeny = vi.fn();
    render(<InterruptApprovalCard toolName="write_note" onApprove={onApprove} onDeny={onDeny} />);
    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    expect(onApprove).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "拒绝" }));
    expect(onDeny).toHaveBeenCalledTimes(1);
  });
});
