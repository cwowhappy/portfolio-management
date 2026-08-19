import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { NextRequest } from "next/server";

// 反代路由只依赖 fetch 与 Response，直接测试其代理/降级行为与 no-store 头。

import { GET as marketGet } from "@/app/api/market/[...path]/route";
import { GET as healthGet } from "@/app/api/agent/health/route";

// 路由仅读取 req.url，普通 Request 即可满足（类型上 NextRequest 多了 nextUrl/cookies 等）
function req(url: string): NextRequest {
  return new Request(url) as unknown as NextRequest;
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
});
