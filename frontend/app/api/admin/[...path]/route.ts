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

// P1-10：admin 设置 MCP token 走 PUT（405 会以无 message 的 502/405 面穿到页面，此处显式导出）
export async function PUT(req: NextRequest) {
  return relay(targetPath(req), "PUT", req, await req.text());
}
