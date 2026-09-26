import { describe, it, expect, vi, afterEach } from "vitest";
import { cleanup, render, screen, fireEvent } from "@testing-library/react";
import FundScreeningForm, { CATEGORY_OPTIONS, TRACKING_ERROR_NOTE } from "@/components/screening/FundScreeningForm";

describe("FundScreeningForm", () => {
  afterEach(() => cleanup());

  it("渲染三数值条件（含单位标注）与类别六桶下拉", () => {
    render(<FundScreeningForm params={{}} onChange={() => {}} onSubmit={() => {}} loading={false} />);
    expect(screen.getByLabelText(/费率/)).toBeTruthy();
    expect(screen.getByLabelText(/规模/)).toBeTruthy();
    expect(screen.getByLabelText(/跟踪误差/)).toBeTruthy();
    // 单位口径标注：费率 %、规模 亿元、TE 小数说明
    expect(screen.getByText(/费率 < %（年化，0\.6=0\.6%）/)).toBeTruthy();
    expect(screen.getByText(/规模 > 亿元/)).toBeTruthy();
    expect(screen.getByText(/0\.05=5%/)).toBeTruthy();
    // 类别六桶 +「全部」空值
    for (const c of CATEGORY_OPTIONS) {
      const opt = screen.getByRole("option", { name: c }) as HTMLOptionElement;
      expect(opt.value).toBe(c);
    }
    expect((screen.getByRole("option", { name: "全部" }) as HTMLOptionElement).value).toBe("");
  });

  it("TE 控件旁有收盘价口径提示（title + 小字）", () => {
    render(<FundScreeningForm params={{}} onChange={() => {}} onSubmit={() => {}} loading={false} />);
    expect(screen.getByText(TRACKING_ERROR_NOTE)).toBeTruthy();
    expect(screen.getByLabelText(/跟踪误差/).getAttribute("title")).toBe(TRACKING_ERROR_NOTE);
  });

  it("输入条件触发 onChange", () => {
    const onChange = vi.fn();
    render(<FundScreeningForm params={{}} onChange={onChange} onSubmit={() => {}} loading={false} />);
    fireEvent.change(screen.getByLabelText(/费率/), { target: { value: "0.6" } });
    expect(onChange).toHaveBeenCalledWith("feeRateMax", "0.6");
  });

  it("空提交预检：不调 onSubmit 并提示至少一个条件", () => {
    const onSubmit = vi.fn();
    render(<FundScreeningForm params={{}} onChange={() => {}} onSubmit={onSubmit} loading={false} />);
    fireEvent.click(screen.getByRole("button", { name: "筛选" }));
    expect(onSubmit).not.toHaveBeenCalled();
    expect(screen.getByText("请至少填写一个筛选条件")).toBeTruthy();
  });

  it("有条件提交调 onSubmit 且清除空条件提示", () => {
    const onSubmit = vi.fn();
    // 先空提交出提示，再有条件提交验证提示清除
    render(<FundScreeningForm params={{ feeRateMax: 0.6 }} onChange={() => {}} onSubmit={onSubmit} loading={false} />);
    fireEvent.click(screen.getByRole("button", { name: "筛选" }));
    expect(onSubmit).toHaveBeenCalledOnce();
    expect(screen.queryByText("请至少填写一个筛选条件")).toBeNull();
  });
});
