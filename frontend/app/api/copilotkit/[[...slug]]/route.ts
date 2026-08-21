// CopilotKit 运行时：浏览器 → /api/copilotkit → 后端 POST /agui/run（AG-UI SSE）。
import {
  CopilotRuntime,
  createCopilotRuntimeHandler,
} from "@copilotkit/runtime/v2";
import { HttpAgent } from "@ag-ui/client";

export const dynamic = "force-dynamic";

const BACKEND = process.env.BACKEND_URL ?? "http://localhost:8080";

// 后端 /agui/run 已按 anyRequest().authenticated() 保护，会话识别依赖浏览器下发的
// JSESSIONID。HttpAgent 的 server-side fetch 不会自动透传浏览器 Cookie，因此这里必须
// 按请求构建运行时，把入站请求的 Cookie 头注入到 HttpAgent（模块级构建拿不到每个请求的 Cookie）。
async function handle(req: Request) {
  const cookie = req.headers.get("cookie") ?? "";
  const runtime = new CopilotRuntime({
    agents: {
      // agent id 与后端 agentscope.agui.default-agent-id 一致
      invest: new HttpAgent({
        url: `${BACKEND}/agui/run`,
        ...(cookie ? { headers: { Cookie: cookie } } : {}),
      }),
    },
  });

  const handler = createCopilotRuntimeHandler({
    runtime,
    basePath: "/api/copilotkit",
  });

  // 给所有响应加 no-store，避免浏览器缓存 /info 的 404 导致连接走 single 模式
  const res = await handler(req);
  const headers = new Headers(res.headers);
  headers.set("Cache-Control", "no-store, no-cache, must-revalidate");
  return new Response(res.body, {
    status: res.status,
    statusText: res.statusText,
    headers,
  });
}

export const GET = handle;
export const POST = handle;
export const OPTIONS = handle;
