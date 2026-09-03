// 反代完整状态检查：/api/agent/status → 后端 /api/agent/status（含行情探活，结构 {status, llm, market}）。
// 与 /api/agent/health（纯 liveness，供 docker healthcheck / Playwright 就绪检测）区分。
import { relay } from "@/lib/proxy";

export const dynamic = "force-dynamic";

export async function GET(req?: Request) {
  return relay("/api/agent/status", "GET", req);
}
