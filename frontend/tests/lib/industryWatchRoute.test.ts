import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, POST } from "@/app/api/industry-watch/[[...path]]/route";
import type { NextRequest } from "next/server";

function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

describe("industry-watch 反代路由", () => {
  const fetchMock = vi.fn();
  beforeEach(() => { vi.stubGlobal("fetch", fetchMock); fetchMock.mockReset(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("GET 根路径拼对上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(req("http://localhost:3000/api/industry-watch"), { params: Promise.resolve({ path: [] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/industry-watch");
  });

  it("POST 透传 JSON body", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const body = '{"industryCode":"801780"}';
    await POST(new Request("http://localhost:3000/api/industry-watch", { method: "POST", body }), { params: Promise.resolve({ path: [] }) });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/industry-watch");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(body);
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("DELETE 路径参数拼对上游", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    await DELETE(req("http://localhost:3000/api/industry-watch/801780"), { params: Promise.resolve({ path: ["801780"] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/industry-watch/801780");
  });
});
