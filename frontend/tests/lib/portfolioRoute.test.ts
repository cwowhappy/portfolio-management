import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PUT } from "@/app/api/portfolio/[...path]/route";
import type { NextRequest } from "next/server";

function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

describe("portfolio 反代路由", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GET 拼对上游路径", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(req("http://localhost:3000/api/portfolio/overview"), {
      params: Promise.resolve({ path: ["overview"] }),
    });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/portfolio/overview");
  });

  it("PUT 透传方法、路径与 body（改名/编辑交易）", async () => {
    fetchMock.mockResolvedValue(new Response('{"name":"东财"}', { status: 200 }));
    const res = await PUT(
      new Request("http://localhost:3000/api/portfolio/groups/7", {
        method: "PUT",
        body: '{"name":"东财"}',
      }),
      { params: Promise.resolve({ path: ["groups", "7"] }) },
    );
    expect(res.status).toBe(200);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/portfolio/groups/7");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe('{"name":"东财"}');
  });
});
