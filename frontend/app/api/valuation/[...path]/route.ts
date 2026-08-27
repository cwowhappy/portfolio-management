// 反代估值 REST：/api/valuation/** → 后端 /api/valuation/**。
import { NextRequest } from "next/server";

export const dynamic = "force-dynamic";
const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

export async function GET(req: NextRequest) {
  const path = req.nextUrl.pathname.replace(/^\/api\/valuation/, "");
  const search = req.nextUrl.search;
  const upstream = await fetch(`${BACKEND}/api/valuation${path}${search}`, {
    headers: { Accept: "application/json" },
    signal: AbortSignal.timeout(15_000),
  });
  const text = await upstream.text();
  return new Response(text, {
    status: upstream.status,
    headers: { "Content-Type": upstream.headers.get("Content-Type") ?? "application/json" },
  });
}
