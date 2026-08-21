// 同源反代：/api/auth/login → 后端 /api/auth/login（透传 Cookie/Set-Cookie）。
import type { NextRequest } from "next/server";
import { BACKEND, inboundCookie, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  const body = await req.text();
  const upstream = await fetch(BACKEND + "/api/auth/login", {
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

export async function GET(req: NextRequest) {
  const upstream = await fetch(BACKEND + "/api/auth/login", {
    headers: inboundCookie(req),
    cache: "no-store",
  });
  return relay(upstream);
}
