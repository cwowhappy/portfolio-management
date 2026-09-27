import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ForgotPasswordForm } from "@/components/auth/ForgotPasswordForm";
import { AuthProvider } from "@/lib/auth";

function jsonResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

describe("ForgotPasswordForm", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    // 默认 /me（挂载时探测会话）401；发码/重置成功响应由各用例按 URL 覆写
    fetchMock.mockResolvedValue(jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderForm() {
    return render(
      <AuthProvider>
        <ForgotPasswordForm />
      </AuthProvider>,
    );
  }

  /** 走完第一步：填 identifier → 发码成功 → 等待第二步渲染。 */
  async function toStepTwo(identifier = "alice") {
    fetchMock.mockImplementation(async (url: string) =>
      url === "/api/auth/reset-code"
        ? jsonResponse({ message: "验证码已发送" })
        : jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401),
    );
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("用户名或邮箱"), {
      target: { value: identifier },
    });
    fireEvent.click(screen.getByText("发送验证码"));
    await waitFor(() => expect(screen.getByText("重置密码")).toBeTruthy());
  }

  it("空 identifier 发码报「请输入用户名或邮箱」且不发请求", async () => {
    renderForm();
    fireEvent.click(screen.getByText("发送验证码"));
    await waitFor(() =>
      expect(screen.getByRole("alert").textContent).toContain("请输入用户名或邮箱"),
    );
    expect(fetchMock.mock.calls.some((c) => c[0] === "/api/auth/reset-code")).toBe(false);
  });

  it("发码成功进入第二步：identifier 锁定，出现验证码/新密码输入", async () => {
    await toStepTwo("alice");
    const identifierInput = screen.getByPlaceholderText("用户名或邮箱") as HTMLInputElement;
    expect(identifierInput.disabled).toBe(true);
    expect(screen.getByPlaceholderText("6 位验证码")).toBeTruthy();
    expect(screen.getByPlaceholderText("新密码（至少 8 位，含字母和数字）")).toBeTruthy();
    expect(screen.queryByText("发送验证码")).toBeNull();
    const codeCall = fetchMock.mock.calls.find((c) => c[0] === "/api/auth/reset-code")!;
    expect(codeCall[1].method).toBe("POST");
    expect(JSON.parse(codeCall[1].body)).toEqual({ identifier: "alice" });
  });

  it("弱新密码提交被前端拦截（不调用重置接口）", async () => {
    await toStepTwo("alice");
    fireEvent.change(screen.getByPlaceholderText("6 位验证码"), { target: { value: "123456" } });
    fireEvent.change(screen.getByPlaceholderText("新密码（至少 8 位，含字母和数字）"), {
      target: { value: "short" },
    });
    fireEvent.click(screen.getByText("重置密码"));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("至少 8 位"));
    expect(fetchMock.mock.calls.some((c) => c[0] === "/api/auth/reset-password")).toBe(false);
  });

  it("重置成功展示完成态", async () => {
    fetchMock.mockImplementation(async (url: string) => {
      if (url === "/api/auth/reset-code") return jsonResponse({ message: "验证码已发送" });
      if (url === "/api/auth/reset-password") return jsonResponse({ message: "密码已重置" });
      return jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401);
    });
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("用户名或邮箱"), { target: { value: "alice" } });
    fireEvent.click(screen.getByText("发送验证码"));
    await waitFor(() => expect(screen.getByPlaceholderText("6 位验证码")).toBeTruthy());
    fireEvent.change(screen.getByPlaceholderText("6 位验证码"), { target: { value: "123456" } });
    fireEvent.change(screen.getByPlaceholderText("新密码（至少 8 位，含字母和数字）"), {
      target: { value: "passw0rd" },
    });
    fireEvent.click(screen.getByText("重置密码"));
    await waitFor(() => expect(screen.getByText(/密码已重置/)).toBeTruthy());
    const resetCall = fetchMock.mock.calls.find((c) => c[0] === "/api/auth/reset-password")!;
    expect(JSON.parse(resetCall[1].body)).toEqual({
      identifier: "alice",
      code: "123456",
      newPassword: "passw0rd",
    });
  });
});
