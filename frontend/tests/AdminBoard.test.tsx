import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import AdminBoard from "@/components/admin/AdminBoard";
import { adminApi, type AdminUserView } from "@/lib/adminApi";

vi.mock("@/lib/adminApi", () => ({
  adminApi: {
    list: vi.fn(),
    approve: vi.fn(),
    reject: vi.fn(),
    enable: vi.fn(),
    disable: vi.fn(),
    resetPassword: vi.fn(),
  },
}));

const api = vi.mocked(adminApi);

const admin: AdminUserView = { id: 1, username: "admin", role: "ADMIN", status: "APPROVED", enabled: true };
const pendingUser: AdminUserView = { id: 2, username: "newbie", role: "USER", status: "PENDING", enabled: true };
const approvedUser: AdminUserView = { id: 3, username: "alice", role: "USER", status: "APPROVED", enabled: true };
const disabledUser: AdminUserView = { id: 4, username: "bob", role: "USER", status: "APPROVED", enabled: false };

const allUsers = [admin, pendingUser, approvedUser, disabledUser];

beforeEach(() => {
  vi.clearAllMocks();
  api.list.mockResolvedValue(allUsers);
  api.approve.mockResolvedValue({ ...pendingUser, status: "APPROVED" });
  api.reject.mockResolvedValue({ ...pendingUser, status: "REJECTED" });
  api.disable.mockResolvedValue({ ...approvedUser, enabled: false });
  api.enable.mockResolvedValue({ ...disabledUser, enabled: true });
  api.resetPassword.mockResolvedValue(approvedUser);
});

afterEach(() => {
  cleanup();
});

describe("AdminBoard", () => {
  it("加载失败时显示错误文案", async () => {
    api.list.mockRejectedValue(new Error("后端不可用"));
    render(<AdminBoard />);
    expect(await screen.findByText("后端不可用")).toBeTruthy();
  });

  it("非 Error 异常回退为默认错误文案", async () => {
    api.list.mockRejectedValue("boom");
    render(<AdminBoard />);
    expect(await screen.findByText("加载用户列表失败")).toBeTruthy();
  });

  it("渲染待审核区与全部用户表（角色/状态/启用标签）", async () => {
    render(<AdminBoard />);
    expect(await screen.findByText("用户管理")).toBeTruthy();
    // 待审核区只有 PENDING 用户（通过/拒绝按钮只出现在待审核卡片上）
    const pendingItem = (await screen.findByRole("button", { name: "通过" })).closest("li");
    expect(pendingItem).toBeTruthy();
    expect(within(pendingItem!).getByText("newbie")).toBeTruthy();
    expect(screen.queryByText("暂无待审核用户")).toBeNull();
    // 全部用户表
    const table = screen.getByRole("table");
    expect(within(table).getByText("管理员")).toBeTruthy();
    expect(within(table).getByText("待审核")).toBeTruthy();
    expect(within(table).getAllByText("已通过")).toHaveLength(3);
    // ADMIN 行无操作按钮（显示 —），且不出现停用/重置密码
    const adminRow = within(table).getByText("admin").closest("tr")!;
    expect(within(adminRow).queryByRole("button")).toBeNull();
    expect(within(adminRow).getByText("—")).toBeTruthy();
  });

  it("无待审核用户时显示空态", async () => {
    api.list.mockResolvedValue([admin, approvedUser]);
    render(<AdminBoard />);
    expect(await screen.findByText("暂无待审核用户")).toBeTruthy();
  });

  it("审核通过：调用 approve 并刷新列表", async () => {
    api.list.mockResolvedValueOnce(allUsers).mockResolvedValueOnce([admin, { ...pendingUser, status: "APPROVED" }, approvedUser, disabledUser]);
    render(<AdminBoard />);
    const approveBtn = await screen.findByRole("button", { name: "通过" });
    expect(approveBtn.closest("li")!.textContent).toContain("newbie");
    fireEvent.click(approveBtn);
    await vi.waitFor(() => expect(api.approve).toHaveBeenCalledWith(2));
    await vi.waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
    // 刷新后 newbie 离开待审核区
    await vi.waitFor(() => expect(screen.getByText("暂无待审核用户")).toBeTruthy());
  });

  it("审核拒绝：调用 reject", async () => {
    render(<AdminBoard />);
    const rejectBtn = await screen.findByRole("button", { name: "拒绝" });
    expect(rejectBtn.closest("li")!.textContent).toContain("newbie");
    fireEvent.click(rejectBtn);
    await vi.waitFor(() => expect(api.reject).toHaveBeenCalledWith(2));
    await vi.waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
  });

  it("操作失败显示页面级错误提示", async () => {
    api.approve.mockRejectedValue(new Error("审批失败"));
    render(<AdminBoard />);
    fireEvent.click(await screen.findByRole("button", { name: "通过" }));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toContain("审批失败");
    // 失败不触发刷新
    expect(api.list).toHaveBeenCalledTimes(1);
  });

  it("停用已启用用户 / 启用已停用用户", async () => {
    render(<AdminBoard />);
    const table = await screen.findByRole("table");
    const aliceRow = within(table).getByText("alice").closest("tr")!;
    fireEvent.click(within(aliceRow).getByRole("button", { name: "停用" }));
    await vi.waitFor(() => expect(api.disable).toHaveBeenCalledWith(3));

    const bobRow = within(table).getByText("bob").closest("tr")!;
    fireEvent.click(within(bobRow).getByRole("button", { name: "启用" }));
    await vi.waitFor(() => expect(api.enable).toHaveBeenCalledWith(4));
  });

  describe("重置密码弹窗", () => {
    async function openDialog() {
      render(<AdminBoard />);
      const table = await screen.findByRole("table");
      const aliceRow = within(table).getByText("alice").closest("tr")!;
      fireEvent.click(within(aliceRow).getByRole("button", { name: "重置密码" }));
      return screen.getByRole("dialog", { name: "为 alice 重置密码" });
    }

    it("点击重置密码打开弹窗，取消后关闭", async () => {
      await openDialog();
      expect(screen.getByText("为 alice 设置新密码")).toBeTruthy();
      fireEvent.click(screen.getByRole("button", { name: "取消" }));
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(api.resetPassword).not.toHaveBeenCalled();
    });

    it("弱密码在前端被拦截（不调用接口）", async () => {
      await openDialog();
      fireEvent.change(screen.getByLabelText("新密码"), { target: { value: "short" } });
      fireEvent.change(screen.getByLabelText("确认新密码"), { target: { value: "short" } });
      fireEvent.click(screen.getByRole("button", { name: "确认重置" }));
      expect(await screen.findByText(/密码至少 8 位/)).toBeTruthy();
      expect(api.resetPassword).not.toHaveBeenCalled();
    });

    it("两次输入不一致时报错", async () => {
      await openDialog();
      fireEvent.change(screen.getByLabelText("新密码"), { target: { value: "Passw0rd123" } });
      fireEvent.change(screen.getByLabelText("确认新密码"), { target: { value: "Passw0rd456" } });
      fireEvent.click(screen.getByRole("button", { name: "确认重置" }));
      expect(await screen.findByText("两次输入的密码不一致")).toBeTruthy();
      expect(api.resetPassword).not.toHaveBeenCalled();
    });

    it("提交成功：调用 resetPassword 并关闭弹窗", async () => {
      await openDialog();
      fireEvent.change(screen.getByLabelText("新密码"), { target: { value: "Passw0rd123" } });
      fireEvent.change(screen.getByLabelText("确认新密码"), { target: { value: "Passw0rd123" } });
      fireEvent.click(screen.getByRole("button", { name: "确认重置" }));
      await vi.waitFor(() => expect(api.resetPassword).toHaveBeenCalledWith(3, "Passw0rd123"));
      await vi.waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    });

    it("提交失败：弹窗关闭并显示页面级错误提示", async () => {
      api.resetPassword.mockRejectedValue(new Error("重置失败"));
      await openDialog();
      fireEvent.change(screen.getByLabelText("新密码"), { target: { value: "Passw0rd123" } });
      fireEvent.change(screen.getByLabelText("确认新密码"), { target: { value: "Passw0rd123" } });
      fireEvent.click(screen.getByRole("button", { name: "确认重置" }));
      const alert = await screen.findByRole("alert");
      expect(alert.textContent).toContain("重置失败");
      await vi.waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    });
  });
});
