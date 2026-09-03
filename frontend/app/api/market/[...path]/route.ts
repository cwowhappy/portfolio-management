// 反代行情 REST：/api/market/** → 后端 /api/market/**（收编到 relay，统一超时/兜底/透传）。
import type { NextRequest } from "next/server";
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
) {
  const { path } = await ctx.params;
  const u = new URL(req.url);
  return relay("/api/market/" + path.join("/") + u.search, "GET", req);
}
