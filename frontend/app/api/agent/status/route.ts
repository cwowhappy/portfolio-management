// 反代完整状态检查：/api/agent/status → 后端 /api/agent/status（含行情探活，结构 {status, llm, market}）。
// 与 /api/agent/health（纯 liveness，供 docker healthcheck / Playwright 就绪检测）区分。
export const dynamic = "force-dynamic";

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET() {
  try {
    const upstream = await fetch(BACKEND + "/api/agent/status", {
      signal: AbortSignal.timeout(15_000),
      cache: "no-store",
    });
    const body = await upstream.text();
    const contentType = upstream.headers.get("content-type") ?? "application/json; charset=utf-8";
    return new Response(body, {
      status: upstream.status,
      headers: { "Content-Type": contentType },
    });
  } catch (e) {
    console.error("[status proxy] 上游请求失败:", e instanceof Error ? e.message : e);
    return Response.json({ status: "degraded", message: "后端不可达" }, { status: 502 });
  }
}
