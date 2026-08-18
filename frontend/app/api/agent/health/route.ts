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
    return new Response(body, {
      status: upstream.status,
      headers: { "Content-Type": "application/json; charset=utf-8" },
    });
  } catch {
    return Response.json({ status: "degraded", message: "后端不可达" }, { status: 502 });
  }
}
