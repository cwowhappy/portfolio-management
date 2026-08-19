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

export const GET = handler;
export const POST = handler;
export const OPTIONS = handler;
