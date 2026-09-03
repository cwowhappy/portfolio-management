import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, POST, PUT } from "@/app/api/allocation/[...path]/route";
import type { NextRequest } from "next/server";

function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

describe("allocation 反代路由", () => {
  const fetchMock = vi.fn();
  beforeEach(() => { vi.stubGlobal("fetch", fetchMock); fetchMock.mockReset(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("GET 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(req("http://localhost:3000/api/allocation/plans"), { params: Promise.resolve({ path: ["plans"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/allocation/plans");
  });

  it("POST 透传 body 与 JSON Content-Type", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":5}', { status: 201 }));
    const body = '{"name":"平衡","source":"TEMPLATE","weights":[{"assetClass":"STOCK","weight":60}]}';
    await POST(new Request("http://localhost:3000/api/allocation/plans", { method: "POST", body }), { params: Promise.resolve({ path: ["plans"] }) });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/allocation/plans");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(body);
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("PUT 透传方法与 body", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":5}', { status: 200 }));
    await PUT(new Request("http://localhost:3000/api/allocation/plans/5", { method: "PUT", body: '{"name":"x","weights":[]}' }), { params: Promise.resolve({ path: ["plans", "5"] }) });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/allocation/plans/5");
    expect(init.method).toBe("PUT");
  });

  it("DELETE 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await DELETE(new Request("http://localhost:3000/api/allocation/plans/5", { method: "DELETE" }), { params: Promise.resolve({ path: ["plans", "5"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/allocation/plans/5");
  });

  it("透传入站 Cookie 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(new Request("http://localhost:3000/api/allocation/plans", { headers: { Cookie: "JSESSIONID=abc" } }), { params: Promise.resolve({ path: ["plans"] }) });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });

  it("GET 透传 query string 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(new Request("http://localhost:3000/api/allocation/plans?active=true"), { params: Promise.resolve({ path: ["plans"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/allocation/plans?active=true");
  });
});
