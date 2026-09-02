import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, POST, PUT } from "@/app/api/journal/[...path]/route";
import type { NextRequest } from "next/server";

function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

describe("journal 反代路由", () => {
  const fetchMock = vi.fn();
  beforeEach(() => { vi.stubGlobal("fetch", fetchMock); fetchMock.mockReset(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("GET 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(req("http://localhost:3000/api/journal/entries"), { params: Promise.resolve({ path: ["entries"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/journal/entries");
  });

  it("POST 透传 body", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":5}', { status: 201 }));
    const body = '{"type":"BUY_MEMO","title":"x","content":"y","eventDate":"2026-09-02"}';
    await POST(new Request("http://localhost:3000/api/journal/entries", { method: "POST", body }), { params: Promise.resolve({ path: ["entries"] }) });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/journal/entries");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(body);
  });

  it("PUT 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":5}', { status: 200 }));
    await PUT(new Request("http://localhost:3000/api/journal/entries/5", { method: "PUT", body: '{}' }), { params: Promise.resolve({ path: ["entries", "5"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/journal/entries/5");
    expect(fetchMock.mock.calls[0][1].method).toBe("PUT");
  });

  it("DELETE 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await DELETE(new Request("http://localhost:3000/api/journal/entries/5", { method: "DELETE" }), { params: Promise.resolve({ path: ["entries", "5"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/journal/entries/5");
  });

  it("透传入站 Cookie 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(new Request("http://localhost:3000/api/journal/entries", { headers: { Cookie: "JSESSIONID=abc" } }), { params: Promise.resolve({ path: ["entries"] }) });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });
});
