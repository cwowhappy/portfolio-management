// 反代健康检查：/api/agent/health → 后端。
export const dynamic = "force-dynamic";

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET() {
  try {
    const upstream = await fetch(BACKEND + "/api/agent/health", {
      signal: AbortSignal.timeout(10_000),
      cache: "no-store",
    });
    const body = await upstream.text();
    const contentType = upstream.headers.get("content-type") ?? "application/json; charset=utf-8";
    return new Response(body, {
      status: upstream.status,
      headers: { "Content-Type": contentType },
    });
  } catch (e) {
    console.error("[health proxy] 上游请求失败:", e instanceof Error ? e.message : e);
    return Response.json({ status: "degraded", message: "后端不可达" }, { status: 502 });
  }
}
