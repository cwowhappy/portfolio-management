import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const routerPush = vi.fn();

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: routerPush }),
}));

import { AuthNav } from "@/components/auth/AuthNav";
import { AuthProvider } from "@/lib/auth";

function jsonResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

const admin = { id: 1, username: "boss", role: "ADMIN", status: "APPROVED", enabled: true };
const user = { id: 2, username: "alice", role: "USER", status: "APPROVED", enabled: true };

describe("AuthNav", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    routerPush.mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  function renderNav(me: unknown, status = 200) {
    fetchMock.mockResolvedValue(jsonResponse(me, status));
    return render(
      <AuthProvider>
        <AuthNav />
      </AuthProvider>,
    );
  }

  it("未登录显示登录/注册链接", async () => {
    renderNav({ code: "UNAUTHENTICATED", message: "未登录" }, 401);
    await waitFor(() => expect(screen.getByText("登录")).toBeTruthy());
    expect(screen.getByText("注册")).toBeTruthy();
  });

  it("已登录显示用户名与退出，普通用户不显示管理", async () => {
    renderNav(user, 200);
    await waitFor(() => expect(screen.getByText("alice")).toBeTruthy());
    expect(screen.getByText("退出")).toBeTruthy();
    expect(screen.queryByText("管理")).toBeNull();
  });

  it("管理员显示管理链接", async () => {
    renderNav(admin, 200);
    await waitFor(() => expect(screen.getByText("管理")).toBeTruthy());
  });

  it("点击退出调 logout 并跳转 /login", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(user, 200)); // /me
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true })); // /logout
    renderNav(user, 200);
    await waitFor(() => expect(screen.getByText("退出")).toBeTruthy());
    fireEvent.click(screen.getByText("退出"));
    await waitFor(() => expect(routerPush).toHaveBeenCalledWith("/login"));
    expect(screen.queryByText("alice")).toBeNull();
  });
});
