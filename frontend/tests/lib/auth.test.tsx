import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { AuthProvider, useAuth, type AuthUser } from "@/lib/auth";

function jsonResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

const approvedAdmin: AuthUser = {
  id: 1,
  username: "admin",
  role: "ADMIN",
  status: "APPROVED",
  enabled: true,
};

function wrapper({ children }: { children: ReactNode }) {
  return <AuthProvider>{children}</AuthProvider>;
}

describe("认证状态（lib/auth）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
  });

  it("初始 loading=true、user=null（/me 尚未返回）", () => {
    // 永不 resolve 的 fetch，让 refresh 停在 pending，锁定首帧 loading 态
    fetchMock.mockReturnValue(new Promise(() => {}));
    const { result } = renderHook(() => useAuth(), { wrapper });
    expect(result.current.loading).toBe(true);
    expect(result.current.user).toBeNull();
  });

  it("/api/auth/me 返回 401 → user=null、loading=false", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401),
    );
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    expect(result.current.user).toBeNull();
  });

  it("login 成功后设置 user 并结束 loading", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401), // 挂载时的 /me
    );
    fetchMock.mockResolvedValueOnce(jsonResponse(approvedAdmin)); // /login
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      await result.current.login("admin", "passw0rd", false);
    });
    expect(result.current.user).toEqual(approvedAdmin);
    expect(result.current.loading).toBe(false);
  });

  it("login 失败时抛后端 message 且不设置 user", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401),
    );
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ code: "BAD_CREDENTIALS", message: "用户名或密码错误" }, 401),
    );
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      await expect(result.current.login("admin", "wrong", false)).rejects.toThrow("用户名或密码错误");
    });
    expect(result.current.user).toBeNull();
  });

  it("logout 后清空 user", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401),
    );
    fetchMock.mockResolvedValueOnce(jsonResponse(approvedAdmin)); // /login
    fetchMock.mockResolvedValueOnce(jsonResponse({ ok: true })); // /logout
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    await act(async () => {
      await result.current.login("admin", "passw0rd", false);
    });
    expect(result.current.user).toEqual(approvedAdmin);
    await act(async () => {
      await result.current.logout();
    });
    expect(result.current.user).toBeNull();
  });

  it("register 成功后返回注册用户（不自动登录）", async () => {
    const pendingUser: AuthUser = {
      id: 2,
      username: "newbie",
      role: "USER",
      status: "PENDING",
      enabled: true,
    };
    fetchMock.mockResolvedValueOnce(
      jsonResponse({ code: "UNAUTHENTICATED", message: "未登录" }, 401),
    );
    fetchMock.mockResolvedValueOnce(jsonResponse(pendingUser, 201)); // /register
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.loading).toBe(false));
    let returned: AuthUser | null = null;
    await act(async () => {
      returned = await result.current.register("newbie", "passw0rd");
    });
    expect(returned).toEqual(pendingUser);
    expect(result.current.user).toBeNull();
  });

  it("useAuth 在 Provider 外抛错", () => {
    expect(() => renderHook(() => useAuth())).toThrow("必须在 AuthProvider 内使用");
  });
});
