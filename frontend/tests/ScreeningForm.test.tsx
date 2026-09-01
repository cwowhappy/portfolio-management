import { describe, it, expect, vi, afterEach } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import ScreeningForm from "@/components/screening/ScreeningForm";

describe("ScreeningForm", () => {
  afterEach(() => cleanup());

  it("渲染五个维度分组与提交按钮", () => {
    render(<ScreeningForm params={{}} industries={[]} onChange={() => {}} onSubmit={() => {}} loading={false} />);
    expect(screen.getByText("估值水平")).toBeTruthy();
    expect(screen.getByText("盈利能力")).toBeTruthy();
    expect(screen.getByText("财务健康")).toBeTruthy();
    expect(screen.getByText("成长与稳定")).toBeTruthy();
    expect(screen.getByText("市值与流动性")).toBeTruthy();
    expect(screen.getByText("筛选")).toBeTruthy();
  });

  it("输入条件触发 onChange", async () => {
    const onChange = vi.fn();
    render(<ScreeningForm params={{}} industries={[]} onChange={onChange} onSubmit={() => {}} loading={false} />);
    await userEvent.type(screen.getByLabelText("PE-TTM <"), "20");
    expect(onChange).toHaveBeenCalled();
  });
});
