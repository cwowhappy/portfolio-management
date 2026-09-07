import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PUT } from "@/app/api/skills/[[...path]]/route";
import type { NextRequest } from "next/server";

function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

describe("skills 反代路由", () => {
  const fetchMock = vi.fn();
  beforeEach(() => { vi.stubGlobal("fetch", fetchMock); fetchMock.mockReset(); });
  afterEach(() => { vi.unstubAllGlobals(); });

  it("GET 裸路径拼对上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(req("http://localhost:3000/api/skills"), { params: Promise.resolve({ path: [] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/skills");
  });

  it("PUT 透传 body 到 /config", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    const body = '{"enabled":["tushare_data"]}';
    await PUT(new Request("http://localhost:3000/api/skills/config", { method: "PUT", body }),
      { params: Promise.resolve({ path: ["config"] }) });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/skills/config");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe(body);
  });

  it("透传入站 Cookie 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(new Request("http://localhost:3000/api/skills", { headers: { Cookie: "JSESSIONID=abc" } }),
      { params: Promise.resolve({ path: [] }) });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });
});
