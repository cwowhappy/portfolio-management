// 同源反代：/api/auth/me → 后端 /api/auth/me（透传 Cookie，返回当前用户 UserView 或 401）。
import type { NextRequest } from "next/server";
import { BACKEND, inboundCookie, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(req: NextRequest) {
  const upstream = await fetch(BACKEND + "/api/auth/me", {
    headers: inboundCookie(req),
    cache: "no-store",
  });
  return relay(upstream);
}
