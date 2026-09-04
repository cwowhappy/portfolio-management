// 反代筛选 REST：/api/screening/** → 后端 /api/screening/**（收编到 relay，统一超时/兜底）。
import type { NextRequest } from "next/server";
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(req: NextRequest) {
  const path = req.nextUrl.pathname.replace(/^\/api\/screening/, "");
  const search = req.nextUrl.search;
  return relay(`/api/screening${path}${search}`, "GET", req);
}
