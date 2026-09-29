import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ChecklistConfirmCard from "@/components/research/ChecklistConfirmCard";
import type { CheckItemResult } from "@/lib/researchSchemas";

// 页面级确认卡（D18：不经 useInterrupt）：真实渲染断 DOM，纯组件零 mock——
// 三态徽标、越过必填理由禁用态、提交回调载荷均直接断言。

afterEach(cleanup);

/** BUY 预检典型产物：规则 PASS/HIT + 规则缺失 UNSET + F01 勾选项。 */
const buyItems: CheckItemResult[] = [
  { metric: "SINGLE_POSITION_RATIO", threshold: 0.3, currentValue: 0.25, outcome: "PASS" },
  { metric: "STOCK_PE_MAX", threshold: 20, currentValue: 25.3, outcome: "HIT" },
  { metric: "INDUSTRY_POSITION_RATIO", threshold: null, currentValue: null, outcome: "UNSET" },
  { metric: "能力圈", threshold: null, currentValue: null, outcome: "PASS" },
  { metric: "安全边际", threshold: null, currentValue: null, outcome: "HIT" },
];

/** SELL 预检追加证伪核对项（outcome=UNSET，metric 为后端 falsifierLabel 自由文本）。 */
const sellItems: CheckItemResult[] = [
  { metric: "能力圈", threshold: null, currentValue: null, outcome: "PASS" },
  { metric: "PRICE_BELOW 13.5000", threshold: 13.5, currentValue: null, outcome: "UNSET" },
  { metric: "食品安全事件", threshold: null, currentValue: null, outcome: "UNSET" },
];

describe("ChecklistConfirmCard", () => {
  it("三态徽标：PASS 通过 / HIT 命中（含阈值与当前值）/ UNSET 规则缺失显示「未设定规则」中性", () => {
    render(<ChecklistConfirmCard items={buyItems} onSubmit={vi.fn()} />);
    // 规则通过项
    expect(screen.getAllByText("通过").length).toBe(2);
    // 命中项：徽标 + 数值行「当前 25.3 / 上限 20」+ 汇总
    expect(screen.getByText("命中 2 项")).toBeTruthy();
    expect(screen.getByText("当前 25.3 / 上限 20")).toBeTruthy();
    // UNSET（规则缺失）中性文案，不以失败色渲染语义
    expect(screen.getByText("未设定规则")).toBeTruthy();
    // metric 中文标签（后端枚举名 → 展示名）
    expect(screen.getByText("计划后单票占比")).toBeTruthy();
    expect(screen.getByText("当前 PE")).toBeTruthy();
  });

  it("证伪核对项（SELL 注入）UNSET 显示「待核对」而非「未设定规则」", () => {
    render(<ChecklistConfirmCard items={sellItems} onSubmit={vi.fn()} />);
    expect(screen.getAllByText("待核对").length).toBe(2);
    expect(screen.queryByText("未设定规则")).toBeNull();
    expect(screen.getByText("PRICE_BELOW 13.5000")).toBeTruthy();
  });

  it("逐项确认：直接提交 CONFIRMED（reason 透传 undefined）", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<ChecklistConfirmCard items={buyItems} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole("button", { name: "逐项确认" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith("CONFIRMED", undefined));
  });

  it("越过必填理由：空理由禁用提交并提示，填写后可提交 OVERRIDDEN", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<ChecklistConfirmCard items={buyItems} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole("button", { name: "越过命中项继续" }));
    const textarea = screen.getByLabelText("越过理由") as HTMLTextAreaElement;
    const submit = screen.getByRole("button", { name: "提交（越过）" }) as HTMLButtonElement;
    // 空理由：提示 + 禁用（不发提交）
    expect(screen.getByText("越过命中项须填写理由")).toBeTruthy();
    expect(submit.disabled).toBe(true);
    // 填写后解禁并提交
    fireEvent.change(textarea, { target: { value: "情绪错杀，投资逻辑未破坏" } });
    expect(submit.disabled).toBe(false);
    fireEvent.click(submit);
    await waitFor(() =>
      expect(onSubmit).toHaveBeenCalledWith("OVERRIDDEN", "情绪错杀，投资逻辑未破坏"),
    );
  });

  it("onSubmit 抛错：错误行内展示、可重试", async () => {
    const onSubmit = vi.fn().mockRejectedValue(new Error("网络中断"));
    render(<ChecklistConfirmCard items={buyItems} onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole("button", { name: "逐项确认" }));
    await waitFor(() => expect(screen.getByText("网络中断")).toBeTruthy());
    // 失败后按钮恢复可点（重试）
    expect((screen.getByRole("button", { name: "逐项确认" }) as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(screen.getByRole("button", { name: "逐项确认" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
  });
});
