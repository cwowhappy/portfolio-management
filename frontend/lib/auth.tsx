"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useState,
  type ReactNode,
} from "react";

export interface AuthUser {
  id: number;
  username: string;
  role: "ADMIN" | "USER";
  status: "PENDING" | "APPROVED" | "REJECTED";
  enabled: boolean;
}

interface AuthContextValue {
  user: AuthUser | null;
  loading: boolean;
  login: (username: string, password: string, rememberMe: boolean) => Promise<AuthUser>;
  logout: () => Promise<void>;
  register: (username: string, password: string) => Promise<AuthUser>;
  refresh: () => Promise<void>;
}

const AuthContext = createContext<AuthContextValue | null>(null);

async function fetchJson(path: string, init?: RequestInit): Promise<any> {
  const res = await fetch(path, {
    cache: "no-store",
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(body?.message ?? "请求失败");
  return body;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    try {
      const me = await fetchJson("/api/auth/me");
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
    const u = await fetchJson("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password, rememberMe }),
    });
    setUser(u);
    return u;
  }, []);

  const logout = useCallback(async () => {
    await fetch("/api/auth/logout", { method: "POST", cache: "no-store" });
    setUser(null);
  }, []);

  const register = useCallback(async (username: string, password: string) => {
    return fetchJson("/api/auth/register", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    });
  }, []);

  return (
    <AuthContext.Provider value={{ user, loading, login, logout, register, refresh }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth 必须在 AuthProvider 内使用");
  return ctx;
}
