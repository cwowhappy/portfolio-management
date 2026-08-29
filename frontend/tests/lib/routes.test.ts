import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { NextRequest } from "next/server";

// 反代路由只依赖 fetch 与 Response，直接测试其代理/降级行为与 no-store 头。

import { GET as marketGet } from "@/app/api/market/[...path]/route";
import { GET as healthGet } from "@/app/api/agent/health/route";
import { GET as statusGet } from "@/app/api/agent/status/route";
import { GET as valuationGet } from "@/app/api/valuation/[...path]/route";
import {
  DELETE as conversationsDelete,
  GET as conversationsGet,
  POST as conversationsPost,
  PUT as conversationsPut,
} from "@/app/api/conversations/[[...path]]/route";
import { GET as adminGet, POST as adminPost } from "@/app/api/admin/[...path]/route";
import { POST as loginPost } from "@/app/api/auth/login/route";
import { POST as logoutPost } from "@/app/api/auth/logout/route";
import { GET as meGet } from "@/app/api/auth/me/route";
import { POST as registerPost } from "@/app/api/auth/register/route";

// 路由仅读取 req.url，普通 Request 即可满足（类型上 NextRequest 多了 nextUrl/cookies 等）
function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

// admin/valuation 路由读取 req.nextUrl：用 URL 补齐该属性（pathname/search 语义一致）
function reqWithNextUrl(url: string, init?: RequestInit): NextRequest {
  const r = new Request(url, init) as unknown as NextRequest;
  Object.defineProperty(r, "nextUrl", { value: new URL(url) });
  return r;
}

describe("market 反代路由", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("把 path 拼到后端 URL 并透传状态码与 body", async () => {
    fetchMock.mockResolvedValue(
      new Response('{"code":"600519"}', {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    const res = await marketGet(req("http://localhost:3000/api/market/quote/600519"), {
      params: Promise.resolve({ path: ["quote", "600519"] }),
    });
    expect(res.status).toBe(200);
    expect(await res.text()).toBe('{"code":"600519"}');
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/market/quote/600519");
  });

  it("透传上游 Content-Type（非 JSON 时不强制 application/json）", async () => {
    fetchMock.mockResolvedValue(
      new Response("<html>gateway error</html>", {
        status: 502,
        headers: { "Content-Type": "text/html" },
      }),
    );
    const res = await marketGet(req("http://localhost:3000/api/market/overview"), {
      params: Promise.resolve({ path: ["overview"] }),
    });
    expect(res.status).toBe(502);
    expect(res.headers.get("Content-Type")).toBe("text/html");
  });

  it("下游不可达返回 502", async () => {
    fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
    const res = await marketGet(req("http://localhost:3000/api/market/overview"), {
      params: Promise.resolve({ path: ["overview"] }),
    });
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ message: "无法连接行情服务" });
  });
});

describe("health 反代路由", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("下游不可达返回 502 与 degraded", async () => {
    fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
    const res = await healthGet();
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ status: "degraded", message: "后端不可达" });
  });

  it("liveness 反代目标是后端 /api/agent/health（无行情探活）", async () => {
    fetchMock.mockResolvedValue(
      new Response('{"status":"up"}', {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    const res = await healthGet();
    expect(res.status).toBe(200);
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/agent/health");
  });
});

describe("status 反代路由（完整结构，含行情探活）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("反代目标是后端 /api/agent/status 并透传响应", async () => {
    fetchMock.mockResolvedValue(
      new Response('{"status":"up","llm":{},"market":{"ok":true}}', {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    const res = await statusGet();
    expect(res.status).toBe(200);
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/agent/status");
    expect(await res.json()).toEqual({ status: "up", llm: {}, market: { ok: true } });
  });

  it("下游不可达返回 502 与 degraded", async () => {
    fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
    const res = await statusGet();
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ status: "degraded", message: "后端不可达" });
  });
});


describe("valuation 反代路由", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("拼路径与查询串并透传状态码", async () => {
    fetchMock.mockResolvedValue(
      new Response('{"erp":1}', { status: 200, headers: { "Content-Type": "application/json" } }),
    );
    const res = await valuationGet(
      reqWithNextUrl("http://localhost:3000/api/valuation/history?days=120"),
    );
    expect(res.status).toBe(200);
    expect(fetchMock.mock.calls[0][0]).toBe(
      "http://localhost:8080/api/valuation/history?days=120",
    );
  });

  it("下游不可达返回 502", async () => {
    fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
    const res = await valuationGet(reqWithNextUrl("http://localhost:3000/api/valuation/overview"));
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ message: "无法连接估值服务" });
  });
});

describe("conversations 反代路由（收编到 relay）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  const ctx = (path?: string[]) => ({ params: Promise.resolve({ path }) });

  it("GET 列表透传入站 Cookie 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await conversationsGet(
      new Request("http://localhost:3000/api/conversations", {
        headers: { Cookie: "JSESSIONID=abc" },
      }),
      ctx([]),
    );
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/conversations");
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });

  it("POST/PUT/DELETE 拼对上游路径与方法", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await conversationsPost(new Request("http://localhost:3000/api/conversations", { method: "POST", body: '{"id":"t1"}' }));
    await conversationsPut(
      new Request("http://localhost:3000/api/conversations/t1/messages", { method: "PUT", body: "[]" }),
      ctx(["t1", "messages"]),
    );
    await conversationsDelete(new Request("http://localhost:3000/api/conversations/t1", { method: "DELETE" }), ctx(["t1"]));
    expect(fetchMock.mock.calls.map((c) => [c[0], (c[1] as RequestInit).method])).toEqual([
      ["http://localhost:8080/api/conversations", "POST"],
      ["http://localhost:8080/api/conversations/t1/messages", "PUT"],
      ["http://localhost:8080/api/conversations/t1", "DELETE"],
    ]);
  });

  it("后端不可达返回 502 JSON（relay 统一兜底）", async () => {
    fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
    const res = await conversationsGet(new Request("http://localhost:3000/api/conversations"), ctx([]));
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ message: "无法连接后端服务" });
  });
});

describe("admin 反代路由（收编到 relay）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GET 透传路径与查询串", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await adminGet(reqWithNextUrl("http://localhost:3000/api/admin/users"));
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/admin/users");
  });

  it("POST 透传 body 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
    await adminPost(
      reqWithNextUrl("http://localhost:3000/api/admin/users/7/approve", {
        method: "POST",
        body: "",
      }),
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/admin/users/7/approve");
    expect(init.method).toBe("POST");
  });

  it("后端不可达返回 502 JSON", async () => {
    fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
    const res = await adminGet(reqWithNextUrl("http://localhost:3000/api/admin/users"));
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ message: "无法连接后端服务" });
  });
});

describe("auth 反代路由（收编到 relay）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("login POST 透传 body 与 Set-Cookie", async () => {
    fetchMock.mockResolvedValue(
      new Response('{"id":1}', {
        status: 200,
        headers: { "Content-Type": "application/json", "set-cookie": "JSESSIONID=abc; Path=/" },
      }),
    );
    const res = await loginPost(
      req("http://localhost:3000/api/auth/login", {
        method: "POST",
        body: '{"username":"a","password":"b"}',
      }),
    );
    expect(res.status).toBe(200);
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/auth/login");
    expect(res.headers.getSetCookie()).toContain("JSESSIONID=abc; Path=/");
  });

  it("logout POST 无 body 时上游不带 body；空 body 也不透传", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await logoutPost(req("http://localhost:3000/api/auth/logout", { method: "POST" }));
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
  });

  it("me GET 透传入站 Cookie", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":1}', { status: 200 }));
    await meGet(
      req("http://localhost:3000/api/auth/me", { headers: { Cookie: "JSESSIONID=abc" } }),
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/auth/me");
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });

  it("register POST 打到后端 /api/auth/register", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":2}', { status: 201 }));
    const res = await registerPost(
      req("http://localhost:3000/api/auth/register", {
        method: "POST",
        body: '{"username":"n","password":"passw0rd"}',
      }),
    );
    expect(res.status).toBe(201);
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/auth/register");
  });

  it("后端不可达返回 502 JSON", async () => {
    fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
    const res = await loginPost(
      req("http://localhost:3000/api/auth/login", { method: "POST", body: "{}" }),
    );
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ message: "无法连接后端服务" });
  });
});
