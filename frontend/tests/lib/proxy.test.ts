import { describe, expect, it } from "vitest";
import { inboundCookie, relay } from "@/lib/proxy";

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
});
