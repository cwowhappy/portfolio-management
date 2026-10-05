// 反代收益分析 REST：/api/analytics/** → 后端 /api/analytics/**（四端点均 GET，overview/nav/trade-stats 无数据透传 204）。
// ctx.params 段已解码：经 joinSegments 逐段重编码（防 %2F/%3F 注入分隔符）并拒 `..`（防逃前缀）。
import { joinSegments, relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(
  req: Request,
  ctx: { params: Promise<{ path: string[] }> },
) {
  const { path } = await ctx.params;
  const joined = joinSegments("/api/analytics", path);
  if (!joined) return Response.json({ message: "非法路径" }, { status: 400 });
  return relay(joined + new URL(req.url).search, "GET", req);
}
