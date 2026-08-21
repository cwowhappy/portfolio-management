// 同源反代：/api/auth/register → 后端 /api/auth/register（透传 Cookie/Set-Cookie）。
import type { NextRequest } from "next/server";
import { BACKEND, inboundCookie, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  const body = await req.text();
  const upstream = await fetch(BACKEND + "/api/auth/register", {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...inboundCookie(req),
    },
    body,
    cache: "no-store",
  });
  return relay(upstream);
}
