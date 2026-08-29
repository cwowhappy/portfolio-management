// 同源反代：/api/auth/logout → 后端 /api/auth/logout（透传 Cookie/Set-Cookie，
// 登出会下发清空的 JSESSIONID 与 remember-me cookie，必须原样带回浏览器）。
import type { NextRequest } from "next/server";
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  const body = await req.text().catch(() => "");
  return relay("/api/auth/logout", "POST", req, body || undefined);
}
