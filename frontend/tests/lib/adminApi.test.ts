import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { adminApi } from "@/lib/adminApi";

function jsonResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

const alice = {
  id: 1,
  username: "alice",
  role: "USER",
  status: "PENDING",
  enabled: true,
};

describe("管理员 REST 客户端（lib/adminApi）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("list() GET /api/admin/users 并返回用户数组", async () => {
    fetchMock.mockResolvedValue(jsonResponse([alice]));
    await expect(adminApi.list()).resolves.toEqual([alice]);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/users",
      expect.objectContaining({ cache: "no-store", credentials: "same-origin" }),
    );
  });

  it("approve/reject/enable/disable 走 POST /{id}/<action> 且不携带请求体", async () => {
    const actions = ["approve", "reject", "enable", "disable"] as const;
    for (const action of actions) {
      fetchMock.mockResolvedValueOnce(jsonResponse({ ...alice, status: "APPROVED" }));
      await adminApi[action](7);
    }
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      "/api/admin/users/7/approve",
      "/api/admin/users/7/reject",
      "/api/admin/users/7/enable",
      "/api/admin/users/7/disable",
    ]);
    for (const call of fetchMock.mock.calls) {
      expect(call[1]).toMatchObject({
        method: "POST",
        cache: "no-store",
        credentials: "same-origin",
      });
      expect((call[1] as RequestInit).body).toBeUndefined();
    }
  });

  it("resetPassword(id, newPassword) POST 携带 {newPassword} 请求体", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ ...alice, status: "APPROVED" }));
    await adminApi.resetPassword(7, "NewPassw0rd");
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/users/7/reset-password",
      expect.objectContaining({ method: "POST" }),
    );
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(JSON.parse(init.body as string)).toEqual({ newPassword: "NewPassw0rd" });
  });

  it("非 2xx 且响应体带 message 时抛出该消息", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ message: "权限不足" }, 403));
    await expect(adminApi.list()).rejects.toThrow("权限不足");
  });

  it("非 2xx 且响应体无 message 时抛默认消息", async () => {
    fetchMock.mockResolvedValue(jsonResponse({}, 500));
    await expect(adminApi.list()).rejects.toThrow("请求失败");
  });

  it("非 2xx 且响应体不是 JSON 时抛默认消息", async () => {
    fetchMock.mockResolvedValue({
      ok: false,
      status: 500,
      json: vi.fn().mockRejectedValue(new SyntaxError("Unexpected token")),
    } as unknown as Response);
    await expect(adminApi.list()).rejects.toThrow("请求失败");
  });

  it("所有请求都带 Content-Type 头", async () => {
    fetchMock.mockResolvedValue(jsonResponse([alice]));
    await adminApi.list();
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });
});
