// 管理员 REST 客户端（经 /api/admin 同源反代，权限由后端 ADMIN 角色把关）。

import { z } from "zod";

export const AdminUserViewSchema = z.object({
  id: z.number(),
  username: z.string(),
  role: z.enum(["ADMIN", "USER"]),
  status: z.enum(["PENDING", "APPROVED", "REJECTED"]),
  enabled: z.boolean(),
});

export type AdminUserView = z.infer<typeof AdminUserViewSchema>;

async function request<T>(path: string, schema: z.ZodType<T>, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    credentials: "same-origin",
    cache: "no-store",
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  const body: unknown = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error((body as { message?: string })?.message ?? "请求失败");
  try {
    return schema.parse(body);
  } catch (e) {
    console.error("[adminApi] 响应 schema 校验失败", path, e);
    throw new Error("数据格式异常");
  }
}

export const adminApi = {
  list: () => request("/api/admin/users", z.array(AdminUserViewSchema)),
  approve: (id: number) =>
    request(`/api/admin/users/${id}/approve`, AdminUserViewSchema, { method: "POST" }),
  reject: (id: number) =>
    request(`/api/admin/users/${id}/reject`, AdminUserViewSchema, { method: "POST" }),
  enable: (id: number) =>
    request(`/api/admin/users/${id}/enable`, AdminUserViewSchema, { method: "POST" }),
  disable: (id: number) =>
    request(`/api/admin/users/${id}/disable`, AdminUserViewSchema, { method: "POST" }),
  resetPassword: (id: number, newPassword: string) =>
    request(`/api/admin/users/${id}/reset-password`, AdminUserViewSchema, {
      method: "POST",
      body: JSON.stringify({ newPassword }),
    }),
};
