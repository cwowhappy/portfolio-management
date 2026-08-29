"use client";

import { z } from "zod";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";

export const AuthUserSchema = z.object({
  id: z.number(),
  username: z.string(),
  role: z.enum(["ADMIN", "USER"]),
  status: z.enum(["PENDING", "APPROVED", "REJECTED"]),
  enabled: z.boolean(),
});

export type AuthUser = z.infer<typeof AuthUserSchema>;

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  login: (username: string, password: string, rememberMe: boolean) => Promise<AuthUser>;
  logout: () => Promise<void>;
  register: (username: string, password: string) => Promise<AuthUser>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

async function fetchJson(path: string, init?: RequestInit): Promise<unknown> {
  const res = await fetch(path, {
    cache: "no-store",
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  const body: unknown = await res.json().catch(() => ({}));
  if (!res.ok) {
    const message = (body as { message?: string })?.message ?? "请求失败";
    throw new Error(message);
  }
  return body;
}

/** 边界校验：后端 UserView drift 在这里报错，而不是把脏数据塞进 context。 */
async function fetchUser(path: string, init?: RequestInit): Promise<AuthUser> {
  try {
    return AuthUserSchema.parse(await fetchJson(path, init));
  } catch (e) {
    if (e instanceof z.ZodError) {
      console.error("[auth] 响应 schema 校验失败", path, e);
      throw new Error("数据格式异常");
    }
    throw e;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const me = await fetchUser("/api/auth/me");
      setUser(me);
    } catch {
      setUser(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  const login = useCallback(async (username: string, password: string, rememberMe: boolean) => {
    const u = await fetchUser("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password, rememberMe }),
    });
    setUser(u);
    return u;
  }, []);

  const logout = useCallback(async () => {
    try {
      await fetch("/api/auth/logout", { method: "POST", cache: "no-store" });
    } finally {
      // 无论登出请求成败，本地会话态都必须清掉，避免后端不可达时 UI 仍显示已登录
      setUser(null);
    }
  }, []);

  const register = useCallback(async (username: string, password: string) => {
    return fetchUser("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
  }, []);

  const value = useMemo(
    () => ({ user, loading, login, logout, register, refresh }),
    [user, loading, login, logout, register, refresh],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth 必须在 AuthProvider 内使用");
  return ctx;
}
