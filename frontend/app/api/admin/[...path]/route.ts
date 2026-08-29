// 同源反代：/api/admin/[...path] → 后端 /api/admin/...（透传 Cookie/Set-Cookie）。
// 权限由后端 hasRole("ADMIN") 把关，非管理员直接 403。
import type { NextRequest } from "next/server";
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

const targetPath = (req: NextRequest) => req.nextUrl.pathname + req.nextUrl.search;

export async function GET(req: NextRequest) {
  return relay(targetPath(req), "GET", req);
}

export async function POST(req: NextRequest) {
  return relay(targetPath(req), "POST", req, await req.text());
}
