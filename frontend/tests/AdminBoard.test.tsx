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
    setEmail: vi.fn(),
    setMcpProviderToken: vi.fn(),
  },
}));

// AdminBoard 内嵌 McpTokenSection（P1-10），其数据源列表走 mcpApi
vi.mock("@/lib/mcpApi", () => ({
  fetchProviders: vi.fn().mockResolvedValue([]),
}));

// AdminBoard 内嵌 ObservabilitySection（MS-30 F1）——子区块自取数，自有测试文件覆盖，此处 mock 壳
vi.mock("@/components/admin/observability/ObservabilitySection", () => ({
  default: () => <section data-testid="observability-section-stub" />,
}));

const api = vi.mocked(adminApi);

const admin: AdminUserView = { id: 1, username: "admin", role: "ADMIN", status: "APPROVED", enabled: true, email: null };
const pendingUser: AdminUserView = { id: 2, username: "newbie", role: "USER", status: "PENDING", enabled: true, email: null };
const approvedUser: AdminUserView = { id: 3, username: "alice", role: "USER", status: "APPROVED", enabled: true, email: "alice@example.com" };
const disabledUser: AdminUserView = { id: 4, username: "bob", role: "USER", status: "APPROVED", enabled: false, email: null };

const allUsers = [admin, pendingUser, approvedUser, disabledUser];

beforeEach(() => {
  vi.clearAllMocks();
  api.list.mockResolvedValue(allUsers);
  api.approve.mockResolvedValue({ ...pendingUser, status: "APPROVED" });
  api.reject.mockResolvedValue({ ...pendingUser, status: "REJECTED" });
  api.disable.mockResolvedValue({ ...approvedUser, enabled: false });
  api.enable.mockResolvedValue({ ...disabledUser, enabled: true });
  api.resetPassword.mockResolvedValue(approvedUser);
  api.setEmail.mockResolvedValue(approvedUser);
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
    // ADMIN 行无操作按钮，邮箱列与操作列均显示 —
    const adminRow = within(table).getByText("admin").closest("tr")!;
    expect(within(adminRow).queryByRole("button")).toBeNull();
    expect(within(adminRow).getAllByText("—")).toHaveLength(2);
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

  it("USER 行渲染「绑定邮箱」按钮与邮箱列；ADMIN 行邮箱列为 —", async () => {
    render(<AdminBoard />);
    const table = await screen.findByRole("table");
    // 已通过 USER：操作组含绑定邮箱，邮箱列展示已绑定地址
    const aliceRow = within(table).getByText("alice").closest("tr")!;
    expect(within(aliceRow).getByRole("button", { name: "绑定邮箱" })).toBeTruthy();
    expect(within(aliceRow).getByText("alice@example.com")).toBeTruthy();
    // 未绑定邮箱的 USER：邮箱列显示「未绑定」
    const bobRow = within(table).getByText("bob").closest("tr")!;
    expect(within(bobRow).getByText("未绑定")).toBeTruthy();
    expect(within(bobRow).getByRole("button", { name: "绑定邮箱" })).toBeTruthy();
    // ADMIN：邮箱列 —，且无绑定邮箱按钮
    const adminRow = within(table).getByText("admin").closest("tr")!;
    expect(within(adminRow).getAllByText("—")).toHaveLength(2); // 邮箱列 + 操作列
    expect(within(adminRow).queryByRole("button", { name: "绑定邮箱" })).toBeNull();
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

    it("IME 组合中 Enter 不提交（isComposing 守卫），组合结束后正常 Enter 提交", async () => {
      await openDialog();
      fireEvent.change(screen.getByLabelText("新密码"), { target: { value: "Passw0rd123" } });
      const confirm = screen.getByLabelText("确认新密码");
      fireEvent.change(confirm, { target: { value: "Passw0rd123" } });
      // IME 组合态 Enter 只应选定候选词：不提交、不阻止默认行为
      const notPrevented = fireEvent.keyDown(confirm, { key: "Enter", isComposing: true });
      expect(notPrevented).toBe(true);
      expect(api.resetPassword).not.toHaveBeenCalled();
      // 组合结束后正常 Enter 恢复提交
      fireEvent.keyDown(confirm, { key: "Enter", isComposing: false });
      await vi.waitFor(() => expect(api.resetPassword).toHaveBeenCalledWith(3, "Passw0rd123"));
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

  describe("绑定邮箱弹窗", () => {
    async function openDialog() {
      render(<AdminBoard />);
      const table = await screen.findByRole("table");
      const aliceRow = within(table).getByText("alice").closest("tr")!;
      fireEvent.click(within(aliceRow).getByRole("button", { name: "绑定邮箱" }));
      return screen.getByRole("dialog", { name: "为 alice 绑定邮箱" });
    }

    it("点击绑定邮箱打开弹窗，取消后关闭", async () => {
      await openDialog();
      expect(screen.getByText("为 alice 绑定邮箱")).toBeTruthy();
      fireEvent.click(screen.getByRole("button", { name: "取消" }));
      expect(screen.queryByRole("dialog")).toBeNull();
      expect(api.setEmail).not.toHaveBeenCalled();
    });

    it("非法邮箱在前端被拦截（不调用接口）", async () => {
      await openDialog();
      fireEvent.change(screen.getByLabelText("邮箱"), { target: { value: "not-an-email" } });
      fireEvent.click(screen.getByRole("button", { name: "确认绑定" }));
      expect(await screen.findByText("请输入正确的邮箱")).toBeTruthy();
      expect(api.setEmail).not.toHaveBeenCalled();
    });

    it("IME 组合中 Enter 不提交（isComposing 守卫），组合结束后正常 Enter 提交", async () => {
      await openDialog();
      const email = screen.getByLabelText("邮箱");
      fireEvent.change(email, { target: { value: "alice@example.com" } });
      // IME 组合态 Enter 只应选定候选词：不提交、不阻止默认行为
      const notPrevented = fireEvent.keyDown(email, { key: "Enter", isComposing: true });
      expect(notPrevented).toBe(true);
      expect(api.setEmail).not.toHaveBeenCalled();
      // 组合结束后正常 Enter 恢复提交
      fireEvent.keyDown(email, { key: "Enter", isComposing: false });
      await vi.waitFor(() => expect(api.setEmail).toHaveBeenCalledWith(3, "alice@example.com"));
    });

    it("提交成功：调用 setEmail 并关闭弹窗", async () => {
      await openDialog();
      fireEvent.change(screen.getByLabelText("邮箱"), { target: { value: "alice@example.com" } });
      fireEvent.click(screen.getByRole("button", { name: "确认绑定" }));
      await vi.waitFor(() => expect(api.setEmail).toHaveBeenCalledWith(3, "alice@example.com"));
      await vi.waitFor(() => expect(api.list).toHaveBeenCalledTimes(2));
      await vi.waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    });

    it("提交失败：弹窗关闭并显示页面级错误提示", async () => {
      api.setEmail.mockRejectedValue(new Error("邮箱已被占用"));
      await openDialog();
      fireEvent.change(screen.getByLabelText("邮箱"), { target: { value: "alice@example.com" } });
      fireEvent.click(screen.getByRole("button", { name: "确认绑定" }));
      const alert = await screen.findByRole("alert");
      expect(alert.textContent).toContain("邮箱已被占用");
      await vi.waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());
    });
  });
});
