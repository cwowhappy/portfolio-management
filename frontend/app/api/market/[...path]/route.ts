// 反代行情 REST：/api/market/** → 后端 /api/market/**（收编到 relay，统一超时/兜底/透传）。
// ctx.params 段已解码：经 joinSegments 逐段重编码（防 %2F/%3F 注入分隔符）并拒 `..`（防逃前缀）。
import type { NextRequest } from "next/server";
import { joinSegments, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(
  req: NextRequest,
  ctx: { params: Promise<{ path: string[] }> },
) {
  const { path } = await ctx.params;
  const joined = joinSegments("/api/market", path);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined + new URL(req.url).search, "GET", req);
}
