// 管理员 REST 客户端（经 /api/admin 同源反代，权限由后端 ADMIN 角色把关）。

import { z } from "zod";

export const AdminUserViewSchema = z.object({
  id: z.number(),
  username: z.string(),
  role: z.enum(["ADMIN", "USER"]),
  status: z.enum(["PENDING", "APPROVED", "REJECTED"]),
  enabled: z.boolean(),
  email: z.string().nullable(),
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

/** 无响应体请求（2xx 即成功，如 204；非 2xx 抛服务端 message）。 */
async function requestNoContent(path: string, init?: RequestInit): Promise<void> {
  const res = await fetch(path, {
    credentials: "same-origin",
    cache: "no-store",
    ...init,
    headers: { "Content-Type": "application/json", ...(init?.headers ?? {}) },
  });
  if (!res.ok) {
    const body: unknown = await res.json().catch(() => ({}));
    throw new Error((body as { message?: string })?.message ?? "请求失败");
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
  setEmail: (id: number, email: string) =>
    request(`/api/admin/users/${id}/email`, AdminUserViewSchema, {
      method: "POST",
      body: JSON.stringify({ email }),
    }),
  /** P1-10：设置/更换 provider token；明文仅在请求体一次经过，响应 204 无回显。 */
  setMcpProviderToken: (code: string, token: string) =>
    requestNoContent(`/api/admin/mcp/providers/${code}/token`, {
      method: "PUT",
      body: JSON.stringify({ token }),
    }),
};
