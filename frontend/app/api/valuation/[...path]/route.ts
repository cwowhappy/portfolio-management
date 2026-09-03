// 反代估值 REST：/api/valuation/** → 后端 /api/valuation/**（收编到 relay，统一超时/兜底）。
import type { NextRequest } from "next/server";
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(req: NextRequest) {
  const path = req.nextUrl.pathname.replace(/^\/api\/valuation/, "");
  const search = req.nextUrl.search;
  return relay(`/api/valuation${path}${search}`, "GET", req);
}
