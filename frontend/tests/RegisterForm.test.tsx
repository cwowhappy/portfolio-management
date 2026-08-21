import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
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
    expect(JSON.parse(regCall[1].body)).toEqual({ username: "newbie", password: "passw0rd" });
  });
});
