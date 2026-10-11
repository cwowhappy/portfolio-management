import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import ReportConfirmCard from "@/components/research/ReportConfirmCard";

// 研报确认卡（§3.10/§4.1）：纯组件零 mock——双态交互（确认定稿/拒绝+理由必填）、
// 防连点（useSaveAction in-flight 守卫）、终态只读展示均直接断言。

afterEach(cleanup);

describe("ReportConfirmCard", () => {
  it("确认定稿：直接提交 CONFIRM（reason 透传 undefined）", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<ReportConfirmCard status="PENDING_CONFIRM" onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole("button", { name: "确认定稿" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith("CONFIRM", undefined));
  });

  it("拒绝必填理由：空理由禁用提交并提示；填写后解禁；返回收起理由框", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<ReportConfirmCard status="PENDING_CONFIRM" onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole("button", { name: "拒绝" }));
    const textarea = screen.getByLabelText("拒绝理由") as HTMLTextAreaElement;
    const submit = screen.getByRole("button", { name: "提交（拒绝）" }) as HTMLButtonElement;
    expect(submit.disabled).toBe(true);
    expect(screen.getByText("拒绝研报须填写理由")).toBeTruthy();
    expect(onSubmit).not.toHaveBeenCalled();

    fireEvent.change(textarea, { target: { value: "假设2批价数据已失效" } });
    expect(submit.disabled).toBe(false);
    // 返回：退回双按钮态，理由框收起
    fireEvent.click(screen.getByRole("button", { name: "返回" }));
    expect(screen.queryByLabelText("拒绝理由")).toBeNull();
    expect(screen.getByRole("button", { name: "确认定稿" })).toBeTruthy();
  });

  it("拒绝提交：携带填写理由调用 REJECT", async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    render(<ReportConfirmCard status="PENDING_CONFIRM" onSubmit={onSubmit} />);
    fireEvent.click(screen.getByRole("button", { name: "拒绝" }));
    fireEvent.change(screen.getByLabelText("拒绝理由"), {
      target: { value: "锚点现值漂移，结论不再成立" },
    });
    fireEvent.click(screen.getByRole("button", { name: "提交（拒绝）" }));
    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith("REJECT", "锚点现值漂移，结论不再成立"));
  });

  it("防连点：提交在途时按钮禁用，连点仅提交一次", async () => {
    let resolveSubmit!: () => void;
    const onSubmit = vi.fn().mockReturnValue(
      new Promise<void>((res) => {
        resolveSubmit = res;
      }),
    );
    render(<ReportConfirmCard status="PENDING_CONFIRM" onSubmit={onSubmit} />);
    const confirm = screen.getByRole("button", { name: "确认定稿" }) as HTMLButtonElement;
    fireEvent.click(confirm);
    expect(confirm.disabled).toBe(true);
    fireEvent.click(confirm); // 在途再点不触发（disabled + useSaveAction in-flight 守卫）
    resolveSubmit();
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(confirm.disabled).toBe(false));
  });

  it("onSubmit 抛错：错误行内展示、可重试", async () => {
    const onSubmit = vi
      .fn()
      .mockRejectedValueOnce(new Error("研报不在待确认状态（已确认/已拒绝或被并发操作）"))
      .mockResolvedValue(undefined);
    render(<ReportConfirmCard status="PENDING_CONFIRM" onSubmit={onSubmit} />);
    const confirm = screen.getByRole("button", { name: "确认定稿" });
    fireEvent.click(confirm);
    expect(
      await screen.findByText("研报不在待确认状态（已确认/已拒绝或被并发操作）"),
    ).toBeTruthy();
    expect((confirm as HTMLButtonElement).disabled).toBe(false);
    fireEvent.click(confirm);
    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(2));
  });

  it("终态只读：CONFIRMED 已定稿 / REJECTED 已拒绝，无操作按钮", () => {
    const { unmount } = render(<ReportConfirmCard status="CONFIRMED" onSubmit={vi.fn()} />);
    expect(screen.getByText("已定稿")).toBeTruthy();
    expect(screen.queryByRole("button")).toBeNull();
    unmount();

    render(<ReportConfirmCard status="REJECTED" onSubmit={vi.fn()} />);
    expect(screen.getByText("已拒绝")).toBeTruthy();
    expect(screen.queryByRole("button")).toBeNull();
  });
});
