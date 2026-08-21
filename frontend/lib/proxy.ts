// 同源反代中继：把上游响应原样带回浏览器。
// 关键点：透传状态码、Content-Type，以及所有 Set-Cookie（登录/登出可能同时下发
// JSESSIONID 与 remember-me 等多个 cookie，必须逐个 append，不能用 set 覆盖）。
// 供 /api/auth/**（以及后续 /api/admin/**）反代路由复用。

export const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function relay(upstream: Response): Promise<Response> {
  const text = await upstream.text();
  const headers = new Headers();
  headers.set(
    "Content-Type",
    upstream.headers.get("content-type") ?? "application/json; charset=utf-8",
  );
  const setCookies = upstream.headers.getSetCookie?.() ?? [];
  const cookies =
    setCookies.length > 0
      ? setCookies
      : upstream.headers.get("set-cookie")
        ? [upstream.headers.get("set-cookie")!]
        : [];
  for (const sc of cookies) {
    headers.append("Set-Cookie", sc);
  }
  return new Response(text, { status: upstream.status, headers });
}

/** 读取入站 Cookie 头，透传为上游请求的 Cookie（保留会话/remember-me）。 */
export function inboundCookie(req: { headers: Headers }): Record<string, string> {
  const cookie = req.headers.get("cookie") ?? "";
  return cookie ? { Cookie: cookie } : {};
}
