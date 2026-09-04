// 同源反代中继：把上游响应原样带回浏览器。
// 关键点：透传状态码、Content-Type，以及所有 Set-Cookie（登录/登出可能同时下发
// JSESSIONID 与 remember-me 等多个 cookie，必须逐个 append，不能用 set 覆盖）。
//
// 提供两种调用方式：
// 1. relay(upstream: Response) —— 调用方自行 fetch 上游后中继（/api/auth/**、/api/admin/**）。
// 2. relay(path, method, req, body?, timeoutMs?) —— 内部 fetch 上游并透传入站 Cookie。
//    透传入站 Cookie 保证后端会话识别；body 存在时补 Content-Type: application/json。
//
// 两种形式统一兜底：上游请求默认 15s 超时（health 等 liveness 可传更短覆盖）；fetch 抛错
// （后端不可达/超时）→ 502 JSON，避免路由抛出未捕获异常变成框架默认的 500 HTML 错误页。

export const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

export const UPSTREAM_TIMEOUT_MS = 15_000;

export async function relay(upstream: Response): Promise<Response>;
export async function relay(
  path: string,
  method: string,
  req?: Request,
  body?: BodyInit,
  timeoutMs?: number,
): Promise<Response>;
export async function relay(
  a: Response | string,
  b?: string,
  req?: Request,
  body?: BodyInit,
  timeoutMs?: number,
): Promise<Response> {
  let upstream: Response;
  try {
    if (typeof a === "string") {
      const cookie = req?.headers.get("cookie") ?? "";
      upstream = await fetch(BACKEND + a, {
        method: b ?? "GET",
        headers: {
          ...(body !== undefined ? { "Content-Type": "application/json" } : {}),
          ...(cookie ? { Cookie: cookie } : {}),
        },
        body,
        cache: "no-store",
        signal: AbortSignal.timeout(timeoutMs ?? UPSTREAM_TIMEOUT_MS),
      });
    } else {
      upstream = a;
    }
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
    // 204/205/304 等状态不允许携带响应体（否则 Response 构造抛错），需置空 body
    const status = upstream.status;
    const responseBody = status === 204 || status === 205 || status === 304 ? null : text;
    return new Response(responseBody, { status, headers });
  } catch (e) {
    console.error("[proxy] 上游请求失败:", e instanceof Error ? e.message : e);
    return Response.json({ message: "无法连接后端服务" }, { status: 502 });
  }
}

/** 读取入站 Cookie 头，透传为上游请求的 Cookie（保留会话/remember-me）。 */
export function inboundCookie(req: { headers: Headers }): Record<string, string> {
  const cookie = req.headers.get("cookie") ?? "";
  return cookie ? { Cookie: cookie } : {};
}
