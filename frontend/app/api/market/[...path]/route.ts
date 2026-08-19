// 反代行情 REST：/api/market/** → 后端 /api/market/**。
import type { NextRequest } from "next/server";

export const dynamic = "force-dynamic";

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
) {
  const { path } = await ctx.params;
  const target = BACKEND + "/api/market/" + path.join("/") + new URL(req.url).search;
  try {
    const upstream = await fetch(target, {
      signal: AbortSignal.timeout(15_000),
      cache: "no-store",
    });
    const body = await upstream.text();
    // 透传上游 Content-Type，避免把网关错误页/非 JSON 响应误标为 application/json
    const contentType = upstream.headers.get("content-type") ?? "application/json; charset=utf-8";
    return new Response(body, {
      status: upstream.status,
      headers: { "Content-Type": contentType },
    });
  } catch (e) {
    console.error("[market proxy] 上游请求失败:", e instanceof Error ? e.message : e);
    return Response.json({ message: "无法连接行情服务" }, { status: 502 });
  }
}
