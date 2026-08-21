// 同源反代：/api/auth/logout → 后端 /api/auth/logout（透传 Cookie/Set-Cookie，
// 登出会下发清空的 JSESSIONID 与 remember-me cookie，必须原样带回浏览器）。
import type { NextRequest } from "next/server";
import { BACKEND, inboundCookie, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  const body = await req.text().catch(() => "");
  const upstream = await fetch(BACKEND + "/api/auth/logout", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...inboundCookie(req),
    },
    body: body || undefined,
    cache: "no-store",
  });
  return relay(upstream);
}
