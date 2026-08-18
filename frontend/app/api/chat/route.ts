// 反代 AG-UI 流：浏览器 → /api/chat → 后端 POST /agui/run（SSE 透传）。
import { NextRequest } from "next/server";

export const dynamic = "force-dynamic";

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function POST(req: NextRequest) {
  const body = await req.text();
  try {
    const upstream = await fetch(BACKEND + "/agui/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body,
      // SSE 长连接，不设超时
      signal: AbortSignal.timeout(10 * 60 * 1000),
    });
    if (!upstream.ok || !upstream.body) {
      let message = "Agent 服务不可用";
      try {
        const err = await upstream.json();
        if (err?.message) message = String(err.message);
      } catch {
        // ignore
      }
      return Response.json({ message }, { status: upstream.status });
    }
    return new Response(upstream.body, {
      status: 200,
      headers: {
        "Content-Type": "text/event-stream; charset=utf-8",
        "Cache-Control": "no-cache, no-transform",
        Connection: "keep-alive",
        "X-Accel-Buffering": "no",
      },
    });
  } catch (e) {
    return Response.json(
      { message: "无法连接 Agent 服务，请确认后端已启动" },
      { status: 502 },
    );
  }
}
