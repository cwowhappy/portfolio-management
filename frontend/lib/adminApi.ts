// 管理员 REST 客户端（经 /api/admin 同源反代，权限由后端 ADMIN 角色把关）。

export interface AdminUserView {
  id: number;
  username: string;
  role: "ADMIN" | "USER";
  status: "PENDING" | "APPROVED" | "REJECTED";
  enabled: boolean;
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    credentials: "same-origin",
    cache: "no-store",
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  const body = (await res.json().catch(() => ({}))) as { message?: string };
  if (!res.ok) throw new Error(body?.message ?? "请求失败");
  return body as T;
}

export const adminApi = {
  list: () => request<AdminUserView[]>("/api/admin/users"),
  approve: (id: number) =>
    request<AdminUserView>(`/api/admin/users/${id}/approve`, { method: "POST" }),
  reject: (id: number) =>
    request<AdminUserView>(`/api/admin/users/${id}/reject`, { method: "POST" }),
  enable: (id: number) =>
    request<AdminUserView>(`/api/admin/users/${id}/enable`, { method: "POST" }),
  disable: (id: number) =>
    request<AdminUserView>(`/api/admin/users/${id}/disable`, { method: "POST" }),
  resetPassword: (id: number, newPassword: string) =>
    request<AdminUserView>(`/api/admin/users/${id}/reset-password`, {
      method: "POST",
      body: JSON.stringify({ newPassword }),
    }),
};
