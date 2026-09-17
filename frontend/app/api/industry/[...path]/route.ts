// 反代行业研究 REST：/api/industry/** → 后端 /api/industry/**（收编到 relay，统一超时/兜底）。
import type { NextRequest } from "next/server";
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(req: NextRequest) {
  const path = req.nextUrl.pathname.replace(/^\/api\/industry/, "");
  const search = req.nextUrl.search;
  return relay(`/api/industry${path}${search}`, "GET", req);
}
