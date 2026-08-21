// 同源反代：/api/admin/[...path] → 后端 /api/admin/...（透传 Cookie/Set-Cookie）。
// 权限由后端 hasRole("ADMIN") 把关，非管理员直接 403。
import type { NextRequest } from "next/server";
import { BACKEND, inboundCookie, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

const target = (req: NextRequest) => BACKEND + req.nextUrl.pathname + req.nextUrl.search;

export async function GET(req: NextRequest) {
  const upstream = await fetch(target(req), {
    headers: inboundCookie(req),
    cache: "no-store",
  });
  return relay(upstream);
}

export async function POST(req: NextRequest) {
  const body = await req.text();
  const upstream = await fetch(target(req), {
    method: "POST",
    headers: {
      "Content-Type": req.headers.get("content-type") ?? "application/json; charset=utf-8",
      ...inboundCookie(req),
    },
    body,
    cache: "no-store",
  });
  return relay(upstream);
}
