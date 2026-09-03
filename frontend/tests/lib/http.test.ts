import { afterEach, describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { get, request } from "@/lib/http";

const ok = (body: unknown, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  json: async () => body,
}) as Response;

const Shape = z.object({ id: z.number() });

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("公共 http 客户端（lib/http）", () => {
  it("GET 成功时按 schema 解析并返回", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ id: 1 })));
    await expect(get<{ id: number }>("/api/x", Shape)).resolves.toEqual({ id: 1 });
  });

  it("request 发送 JSON body 与 Content-Type，方法/缓存正确", async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok({ id: 2 }, 201));
    vi.stubGlobal("fetch", fetchMock);
    await request("/api/x", "POST", { id: 2 }, Shape);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/x");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({ id: 2 }));
    expect(init.headers).toEqual({ "Content-Type": "application/json" });
    expect(init.cache).toBe("no-store");
  });

  it("GET 不带 body 时不发 Content-Type，并禁用缓存", async () => {
    const fetchMock = vi.fn().mockResolvedValue(ok({ id: 1 }));
    vi.stubGlobal("fetch", fetchMock);
    await get("/api/x", Shape);
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe("GET");
    expect(init.body).toBeUndefined();
    expect(init.headers).toBeUndefined();
    expect(init).toEqual(expect.objectContaining({ cache: "no-store" }));
  });

  it("非 2xx 且响应带 message 时抛后端消息", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ message: "卖出数量超过持仓" }, 400)));
    await expect(get("/api/x", Shape)).rejects.toThrow("卖出数量超过持仓");
  });

  it("非 2xx 且响应无 message 时回退「请求失败」", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ error: "internal" }, 500)));
    await expect(get("/api/x", Shape)).rejects.toThrow("请求失败");
  });

  it("非 2xx 且响应体非 JSON 时回退「请求失败」", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({ ok: false, status: 502, json: async () => { throw new Error("not json"); } }),
    );
    await expect(get("/api/x", Shape)).rejects.toThrow("请求失败");
  });

  it("204 时返回 undefined（不解析 body）", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 204,
      json: async () => { throw new Error("no body"); },
    });
    vi.stubGlobal("fetch", fetchMock);
    await expect(request<void>("/api/x", "DELETE")).resolves.toBeUndefined();
  });

  it("响应不符合 schema 时抛「数据格式异常」", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(ok({ id: "oops" })));
    await expect(get("/api/x", Shape)).rejects.toThrow("数据格式异常");
  });
});
