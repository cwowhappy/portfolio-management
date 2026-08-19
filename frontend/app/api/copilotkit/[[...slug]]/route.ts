// CopilotKit 运行时：浏览器 → /api/copilotkit → 后端 POST /agui/run（AG-UI SSE）。
import {
  CopilotRuntime,
  createCopilotRuntimeHandler,
} from "@copilotkit/runtime/v2";
import { HttpAgent } from "@ag-ui/client";

export const dynamic = "force-dynamic";

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

const runtime = new CopilotRuntime({
  agents: {
    // agent id 与后端 agentscope.agui.default-agent-id 一致
    invest: new HttpAgent({ url: `${BACKEND}/agui/run` }),
  },
});

const handler = createCopilotRuntimeHandler({
  runtime,
  basePath: "/api/copilotkit",
});

// 给所有响应加 no-store，避免浏览器缓存 /info 的 404 导致连接走 single 模式
async function noStore(req: Request) {
  const res = await handler(req);
  const headers = new Headers(res.headers);
  headers.set("Cache-Control", "no-store, no-cache, must-revalidate");
  return new Response(res.body, {
    status: res.status,
    statusText: res.statusText,
    headers,
  });
}

export const GET = noStore;
export const POST = noStore;
export const OPTIONS = noStore;
