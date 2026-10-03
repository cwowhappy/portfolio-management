import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, POST, PUT } from "@/app/api/intelligence/[...path]/route";

function req(url: string, init?: RequestInit): Request {
  return new Request(url, init);
}

const ctx = (path?: string[]) => ({ params: Promise.resolve({ path }) });

describe("intelligence 反代路由", () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GET 拼路径与查询串并透传入站 Cookie", async () => {
    fetchMock.mockResolvedValue(new Response('{"items":[]}', { status: 200 }));
    await GET(
      req("http://localhost:3000/api/intelligence/news?page=2&pageSize=50", {
        headers: { Cookie: "JSESSIONID=abc" },
      }),
      ctx(["news"]),
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/intelligence/news?page=2&pageSize=50");
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });

  it("POST（绑定码生成）拼多级路径并透传方法与 body", async () => {
    fetchMock.mockResolvedValue(new Response('{"code":"482913"}', { status: 201 }));
    await POST(
      req("http://localhost:3000/api/intelligence/subscription/binding-code", { method: "POST", body: "" }),
      ctx(["subscription", "binding-code"]),
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/intelligence/subscription/binding-code");
    expect(init.method).toBe("POST");
  });

  it("PUT（订阅保存）透传 JSON body 与 Content-Type", async () => {
    fetchMock.mockResolvedValue(new Response('{"pushEnabled":true}', { status: 200 }));
    const body = '{"pushEnabled":true,"industries":[],"stocks":[]}';
    await PUT(
      req("http://localhost:3000/api/intelligence/subscription", { method: "PUT", body }),
      ctx(["subscription"]),
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/intelligence/subscription");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe(body);
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("DELETE（解绑）拼对上游路径并透传 204", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const res = await DELETE(
      req("http://localhost:3000/api/intelligence/subscription/binding", { method: "DELETE" }),
      ctx(["subscription", "binding"]),
    );
    expect(fetchMock.mock.calls[0][0]).toBe(
      "http://localhost:8080/api/intelligence/subscription/binding",
    );
    expect(res.status).toBe(204);
    expect(await res.text()).toBe("");
  });

  it("下游不可达返回 502 JSON（relay 统一兜底）", async () => {
    fetchMock.mockRejectedValue(new Error("ECONNREFUSED"));
    const res = await GET(req("http://localhost:3000/api/intelligence/macro"), ctx(["macro"]));
    expect(res.status).toBe(502);
    expect(await res.json()).toEqual({ message: "无法连接后端服务" });
  });
});
