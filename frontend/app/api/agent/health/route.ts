// 反代健康检查：/api/agent/health → 后端。
// 纯 liveness（供 docker healthcheck / Playwright 就绪检测）：10s 快速超时兜底，
// 比其余反代的 15s 更快判定后端不可达。
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  return relay("/api/agent/health", "GET", req, undefined, 10_000);
}
