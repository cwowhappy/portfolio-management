import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { RegisterForm } from "@/components/auth/RegisterForm";
import { AuthProvider } from "@/lib/auth";

function jsonResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

const pendingUser = {
  id: 2,
  username: "newbie",
  role: "USER",
  status: "PENDING",
  enabled: true,
};

describe("RegisterForm", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    fetchMock.mockResolvedValue(jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderForm() {
    return render(
      <AuthProvider>
        <RegisterForm />
      </AuthProvider>,
    );
  }

  function fillValid() {
    fireEvent.change(screen.getByPlaceholderText("用户名"), { target: { value: "newbie" } });
    fireEvent.change(screen.getByPlaceholderText("至少 8 位，含字母和数字"), {
      target: { value: "passw0rd" },
    });
    fireEvent.change(screen.getByPlaceholderText("再次输入密码"), {
      target: { value: "passw0rd" },
    });
    fireEvent.change(screen.getByPlaceholderText("邮箱"), {
      target: { value: "newbie@example.com" },
    });
    fireEvent.change(screen.getByPlaceholderText("6 位验证码"), { target: { value: "123456" } });
  }

  it("两次密码不一致时报错", async () => {
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("用户名"), { target: { value: "newbie" } });
    fireEvent.change(screen.getByPlaceholderText("至少 8 位，含字母和数字"), {
      target: { value: "passw0rd" },
    });
    fireEvent.change(screen.getByPlaceholderText("再次输入密码"), {
      target: { value: "passw0rdX" },
    });
    fireEvent.click(screen.getByText("注 册"));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("两次输入的密码不一致"));
  });

  it("密码强度不足时报错", async () => {
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("用户名"), { target: { value: "newbie" } });
    fireEvent.change(screen.getByPlaceholderText("至少 8 位，含字母和数字"), {
      target: { value: "short" },
    });
    fireEvent.change(screen.getByPlaceholderText("再次输入密码"), {
      target: { value: "short" },
    });
    fireEvent.click(screen.getByText("注 册"));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("至少 8 位"));
  });

  it("注册成功后展示等待审核", async () => {
    // /me（挂载时）返回 401；/register 返回 201 待审核用户
    fetchMock.mockImplementation(async (url: string) =>
      url === "/api/auth/register" ? jsonResponse(pendingUser, 201) : jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401),
    );
    renderForm();
    fillValid();
    fireEvent.click(screen.getByText("注 册"));
    await waitFor(() => expect(screen.getByText(/注册成功/)).toBeTruthy());
    expect(screen.getByText(/等待管理员审核/)).toBeTruthy();
    const regCall = fetchMock.mock.calls.find((c) => c[0] === "/api/auth/register")!;
    expect(JSON.parse(regCall[1].body)).toEqual({
      username: "newbie",
      password: "passw0rd",
      email: "newbie@example.com",
      code: "123456",
    });
  });

  it("填全字段点「获取验证码」调用 sendRegisterCode", async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url === "/api/auth/register-code"
        ? jsonResponse({ message: "验证码已发送" })
        : jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401),
    );
    renderForm();
    fillValid();
    fireEvent.click(screen.getByText("获取验证码"));
    await waitFor(() => {
      const codeCall = fetchMock.mock.calls.find((c) => c[0] === "/api/auth/register-code");
      expect(codeCall).toBeTruthy();
      expect(codeCall![1].method).toBe("POST");
      expect(JSON.parse(codeCall![1].body)).toEqual({
        username: "newbie",
        password: "passw0rd",
        email: "newbie@example.com",
      });
    });
  });

  it("邮箱格式不对点「获取验证码」提示且不发请求", async () => {
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("用户名"), { target: { value: "newbie" } });
    fireEvent.change(screen.getByPlaceholderText("至少 8 位，含字母和数字"), {
      target: { value: "passw0rd" },
    });
    fireEvent.change(screen.getByPlaceholderText("邮箱"), { target: { value: "not-an-email" } });
    fireEvent.click(screen.getByText("获取验证码"));
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain("请输入正确的邮箱"),
    );
    expect(fetchMock.mock.calls.some((c) => c[0] === "/api/auth/register-code")).toBe(false);
  });

  it("不发码直接提交提示「请输入邮箱验证码」", async () => {
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("用户名"), { target: { value: "newbie" } });
    fireEvent.change(screen.getByPlaceholderText("至少 8 位，含字母和数字"), {
      target: { value: "passw0rd" },
    });
    fireEvent.change(screen.getByPlaceholderText("再次输入密码"), {
      target: { value: "passw0rd" },
    });
    fireEvent.click(screen.getByText("注 册"));
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain("请输入邮箱验证码"),
    );
    expect(fetchMock.mock.calls.some((c) => c[0] === "/api/auth/register")).toBe(false);
  });

  it("发送验证码后 60s 倒计时内禁用重发，unmount 清理定时器", async () => {
    vi.useFakeTimers();
    try {
      fetchMock.mockImplementation(async (url: string) =>
        url === "/api/auth/register-code"
          ? jsonResponse({ message: "验证码已发送" })
          : jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401),
      );
      const { unmount } = renderForm();
      await act(async () => {}); // 冲掉挂载时的 /me 请求
      fillValid();
      await act(async () => {
        fireEvent.click(screen.getByText("获取验证码"));
      });
      // 60s 起跳，倒计时内按钮禁用
      const resendBtn = screen.getByText("60s 后重发").closest("button")!;
      expect(resendBtn.disabled).toBe(true);
      act(() => {
        vi.advanceTimersByTime(1000);
      });
      expect(screen.getByText("59s 后重发")).toBeTruthy();
      const timersWithInterval = vi.getTimerCount();
      expect(timersWithInterval).toBeGreaterThan(0);
      // unmount 必须清掉倒计时 interval，否则泄漏到下一用例
      unmount();
      expect(vi.getTimerCount()).toBeLessThan(timersWithInterval);
      act(() => {
        vi.advanceTimersByTime(120_000);
      });
    } finally {
      vi.useRealTimers();
    }
  });
});
