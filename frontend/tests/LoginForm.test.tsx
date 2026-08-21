import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const routerReplace = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: routerReplace }),
}));

import { LoginForm } from "@/components/auth/LoginForm";
import { AuthProvider } from "@/lib/auth";

function jsonResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

const approvedUser = {
  id: 1,
  username: "alice",
  role: "USER",
  status: "APPROVED",
  enabled: true,
};

describe("LoginForm", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    routerReplace.mockReset();
    // 挂载时 /me 返回 401
    fetchMock.mockResolvedValue(jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401));
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderForm() {
    return render(
      <AuthProvider>
        <LoginForm />
      </AuthProvider>,
    );
  }

  it("渲染用户名/密码/记住我控件", async () => {
    renderForm();
    await waitFor(() => expect(screen.getByText("登 录")).toBeTruthy());
    expect(screen.getByText("记住我（30 天内免登录）")).toBeTruthy();
  });

  it("提交后调用 login 并携带 rememberMe", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(approvedUser)); // /login
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("用户名"), { target: { value: "alice" } });
    fireEvent.change(screen.getByPlaceholderText("密码"), { target: { value: "passw0rd" } });
    fireEvent.click(screen.getByText("记住我（30 天内免登录）"));
    fireEvent.click(screen.getByText("登 录"));

    await waitFor(() => {
      const loginCall = fetchMock.mock.calls.find((c) => c[0] === "/api/auth/login");
      expect(loginCall).toBeTruthy();
    });
    const loginCall = fetchMock.mock.calls.find((c) => c[0] === "/api/auth/login")!;
    expect(JSON.parse(loginCall[1].body)).toEqual({
      username: "alice",
      password: "passw0rd",
      rememberMe: true,
    });
  });

  it("登录失败展示后端 message", async () => {
    // /me（挂载时）返回 401；/login 返回 401 错误提示
    fetchMock.mockImplementation(async (url: string) =>
      url === "/api/auth/login"
        ? jsonResponse({ code: "BAD_CREDENTIALS", message: "用户名或密码错误" }, 401)
        : jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401),
    );
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("用户名"), { target: { value: "alice" } });
    fireEvent.change(screen.getByPlaceholderText("密码"), { target: { value: "wrong" } });
    fireEvent.click(screen.getByText("登 录"));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("用户名或密码错误"));
  });

  it("空表单提交提示输入用户名和密码", async () => {
    renderForm();
    fireEvent.click(screen.getByText("登 录"));
    await waitFor(() => expect(screen.getByRole("alert").textContent).toContain("请输入用户名和密码"));
  });
});
