import { afterEach, describe, expect, it, vi } from "vitest";
import { inboundCookie, relay } from "@/lib/proxy";

afterEach(() => {
  vi.unstubAllGlobals();
});

function upstream(opts: {
  status?: number;
  body?: string;
  contentType?: string | null;
  setCookies?: string[];
}): Response {
  const headers = new Headers();
  if (opts.contentType !== null && opts.contentType !== undefined) {
    headers.set("content-type", opts.contentType);
  }
  for (const sc of opts.setCookies ?? []) headers.append("set-cookie", sc);
  return new Response(opts.body ?? "{}", { status: opts.status ?? 200, headers });
}

describe("反代中继（lib/proxy）", () => {
  it("透传状态码与响应体", async () => {
    const res = await relay(upstream({ status: 403, body: '{"code":"ACCOUNT_PENDING"}' }));
    expect(res.status).toBe(403);
    await expect(res.text()).resolves.toBe('{"code":"ACCOUNT_PENDING"}');
  });

  it("透传上游 Content-Type", async () => {
    const res = await relay(upstream({ contentType: "application/json;charset=UTF-8" }));
    expect(res.headers.get("content-type")).toContain("application/json");
  });

  it("上游无 Content-Type 时默认 JSON", async () => {
    // new Response() 总会带上 text/plain；这里模拟一个不含 content-type 的上游响应头
    const bare = {
      status: 200,
      headers: new Headers(),
      text: async () => "{}",
    } as unknown as Response;
    const res = await relay(bare);
    expect(res.headers.get("content-type")).toContain("application/json");
  });

  it("透传全部 Set-Cookie（多值不丢失）", async () => {
    const res = await relay(
      upstream({ setCookies: ["JSESSIONID=abc; Path=/", "remember-me=xyz; Max-Age=2592000; Path=/"] }),
    );
    const cookies = res.headers.getSetCookie();
    expect(cookies).toHaveLength(2);
    expect(cookies[0]).toBe("JSESSIONID=abc; Path=/");
    expect(cookies[1]).toBe("remember-me=xyz; Max-Age=2592000; Path=/");
  });

  it("inboundCookie：无 Cookie 头时返回空对象", () => {
    expect(inboundCookie({ headers: new Headers() })).toEqual({});
  });

  it("inboundCookie：有 Cookie 头时原样透传", () => {
    const headers = new Headers({ Cookie: "JSESSIONID=abc" });
    expect(inboundCookie({ headers })).toEqual({ Cookie: "JSESSIONID=abc" });
  });

  it("path 形式：透传入站 Cookie 并转发到上游", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(
      new Response('{"ok":true}', {
        status: 200,
        headers: { "Content-Type": "application/json", "set-cookie": "JSESSIONID=abc; Path=/" },
      }),
    );
    vi.stubGlobal("fetch", fetchSpy);
    const req = { headers: new Headers({ Cookie: "JSESSIONID=abc" }) } as unknown as Request;
    const res = await relay("/api/conversations", "GET", req);
    expect(res.status).toBe(200);
    await expect(res.text()).resolves.toBe('{"ok":true}');
    const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/conversations");
    const headers = init.headers as Record<string, string>;
    expect(headers.Cookie).toBe("JSESSIONID=abc");
    expect(headers["Content-Type"]).toBeUndefined();
  });

  it("path 形式：body 存在时补 Content-Type 并透传", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchSpy);
    const req = { headers: new Headers() } as unknown as Request;
    await relay("/api/conversations", "POST", req, '{"id":"x"}');
    const [, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe("POST");
    expect(init.body).toBe('{"id":"x"}');
    const headers = init.headers as Record<string, string>;
    expect(headers["Content-Type"]).toContain("application/json");
  });

  it("path 形式：上游请求带 15s 超时 signal", async () => {
    const fetchSpy = vi.fn().mockResolvedValue(new Response("{}"));
    vi.stubGlobal("fetch", fetchSpy);
    const req = { headers: new Headers() } as unknown as Request;
    await relay("/api/conversations", "GET", req);
    const [, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
    expect(init.signal).toBeInstanceOf(AbortSignal);
  });

  it("path 形式：后端不可达（fetch 抛错）→ 502 JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new Error("ECONNREFUSED")),
    );
    const req = { headers: new Headers() } as unknown as Request;
    const res = await relay("/api/conversations", "GET", req);
    expect(res.status).toBe(502);
    expect(res.headers.get("content-type")).toContain("application/json");
    await expect(res.json()).resolves.toEqual({ message: "无法连接后端服务" });
  });

  it("path 形式：上游超时（AbortError）→ 502 JSON", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new DOMException("The operation timed out", "TimeoutError")),
    );
    const req = { headers: new Headers() } as unknown as Request;
    const res = await relay("/api/conversations", "GET", req);
    expect(res.status).toBe(502);
    await expect(res.json()).resolves.toEqual({ message: "无法连接后端服务" });
  });
});
