// 同源反代：/api/auth/[...path] → 后端 /api/auth/...（透传 Cookie/Set-Cookie）。
// 兜底 register-code / reset-code / reset-password 等验证码类 POST 端点；
// login / logout / me / register 为具体路由优先匹配，不受本 catch-all 影响。
import type { NextRequest } from "next/server";
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function POST(req: NextRequest) {
  return relay(req.nextUrl.pathname, "POST", req, await req.text());
}
