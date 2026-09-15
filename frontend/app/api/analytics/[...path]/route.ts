// 反代收益分析 REST：/api/analytics/** → 后端 /api/analytics/**（四端点均 GET，overview/nav/trade-stats 无数据透传 204）。
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(
  req: Request,
  ctx: { params: Promise<{ path: string[] }> },
) {
  const { path } = await ctx.params;
  const u = new URL(req.url);
  return relay("/api/analytics/" + path.join("/") + u.search, "GET", req);
}
