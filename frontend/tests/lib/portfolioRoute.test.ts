import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, POST, PUT } from "@/app/api/portfolio/[...path]/route";
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

  it("POST 拼对上游路径并透传 body 与 JSON Content-Type（买入/分红）", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":5}', { status: 201 }));
    const body = '{"groupId":1,"stockCode":"600519","price":110,"quantity":100}';
    const res = await POST(
      new Request("http://localhost:3000/api/portfolio/positions/buy", {
        method: "POST",
        body,
      }),
      { params: Promise.resolve({ path: ["positions", "buy"] }) },
    );
    expect(res.status).toBe(201);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/portfolio/positions/buy");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(body);
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("DELETE 拼对上游路径与方法，且不带 body", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const res = await DELETE(
      new Request("http://localhost:3000/api/portfolio/positions/5", { method: "DELETE" }),
      { params: Promise.resolve({ path: ["positions", "5"] }) },
    );
    expect(res.status).toBe(204);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/portfolio/positions/5");
    expect(init.method).toBe("DELETE");
    expect(init.body).toBeUndefined();
  });

  it("path 为空时拼到 /api/portfolio 根路径", async () => {
    fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
    await GET(req("http://localhost:3000/api/portfolio"), {
      params: Promise.resolve({ path: undefined }),
    });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/portfolio");
  });

  it("GET 透传入站 Cookie 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(
      new Request("http://localhost:3000/api/portfolio/overview", {
        headers: { Cookie: "JSESSIONID=abc" },
      }),
      { params: Promise.resolve({ path: ["overview"] }) },
    );
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });

  it("POST 同时透传入站 Cookie 与 body", async () => {
    fetchMock.mockResolvedValue(new Response('{"id":9}', { status: 200 }));
    await POST(
      new Request("http://localhost:3000/api/portfolio/groups", {
        method: "POST",
        headers: { Cookie: "JSESSIONID=xyz" },
        body: '{"name":"华泰","type":"ACCOUNT"}',
      }),
      { params: Promise.resolve({ path: ["groups"] }) },
    );
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=xyz");
    expect(init.body).toBe('{"name":"华泰","type":"ACCOUNT"}');
  });

  it("透传上游 Set-Cookie", async () => {
    fetchMock.mockResolvedValue(
      new Response("{}", {
        status: 200,
        headers: { "set-cookie": "JSESSIONID=new; Path=/" },
      }),
    );
    const res = await GET(req("http://localhost:3000/api/portfolio/overview"), {
      params: Promise.resolve({ path: ["overview"] }),
    });
    expect(res.headers.getSetCookie()).toContain("JSESSIONID=new; Path=/");
  });

  it("透传上游非 2xx 状态码与 body（如 404）", async () => {
    fetchMock.mockResolvedValue(
      new Response('{"message":"分组不存在"}', {
        status: 404,
        headers: { "Content-Type": "application/json" },
      }),
    );
    const res = await DELETE(
      new Request("http://localhost:3000/api/portfolio/groups/99", { method: "DELETE" }),
      { params: Promise.resolve({ path: ["groups", "99"] }) },
    );
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ message: "分组不存在" });
  });

  it("GET 后端不可达返回 502 JSON", async () => {
    fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
    const res = await GET(req("http://localhost:3000/api/portfolio/overview"), {
      params: Promise.resolve({ path: ["overview"] }),
    });
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ message: "无法连接后端服务" });
  });

  it("POST 后端不可达同样返回 502 JSON", async () => {
    fetchMock.mockRejectedValue(new Error("socket hang up"));
    const res = await POST(
      new Request("http://localhost:3000/api/portfolio/positions/sell", {
        method: "POST",
        body: '{"positionId":5}',
      }),
      { params: Promise.resolve({ path: ["positions", "sell"] }) },
    );
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ message: "无法连接后端服务" });
  });
});
