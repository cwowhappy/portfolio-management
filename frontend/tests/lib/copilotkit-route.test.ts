import { beforeEach, describe, expect, it, vi } from "vitest";

const httpAgentConfigs: Array<Record<string, unknown>> = [];
const handlerBodies: unknown[] = [];

// 隔离 CopilotKit 运行时依赖，仅验证路由层行为：
// 1) 所有响应追加 no-store（回归 4c8c4545）
// 2) 透传入站 Cookie 给后端 HttpAgent（回归：/agui/run 需要会话，cookie 丢失会 401）
// 3) trimToLatestUserMessage 只裁剪 input.messages，resume 轮（messages 为空）原样透传
vi.mock("@copilotkit/runtime/v2", () => ({
  CopilotRuntime: class {},
  createCopilotRuntimeHandler: () => async (req: Request) => {
    // GET 等无 JSON body 的请求捕获时容错跳过
    try {
      handlerBodies.push(await req.clone().json());
    } catch {
      /* 非 JSON 请求体不捕获 */
    }
    return new Response("ok", { status: 200 });
  },
}));
vi.mock("@ag-ui/client", () => ({
  HttpAgent: class {
    constructor(config: Record<string, unknown>) {
      httpAgentConfigs.push(config);
    }
  },
}));

import { GET, POST } from "@/app/api/copilotkit/[[...slug]]/route";

describe("CopilotKit 反代路由", () => {
  beforeEach(() => {
    httpAgentConfigs.length = 0;
    handlerBodies.length = 0;
  });

  it("所有响应都带 Cache-Control: no-store", async () => {
    const res = await GET(new Request("http://localhost:3000/api/copilotkit/info"));
    expect(res.headers.get("Cache-Control")).toBe("no-store, no-cache, must-revalidate");
  });

  it("携带 Cookie 的请求把 JSESSIONID 透传给后端 HttpAgent", async () => {
    await POST(
      new Request("http://localhost:3000/api/copilotkit/invest/run", {
        method: "POST",
        headers: { Cookie: "JSESSIONID=abc123", "Content-Type": "application/json" },
        body: JSON.stringify({}),
      }),
    );
    expect(httpAgentConfigs).toHaveLength(1);
    expect(httpAgentConfigs[0].headers).toEqual({ Cookie: "JSESSIONID=abc123" });
  });

  it("无 Cookie 的请求不注入 Cookie 头", async () => {
    await POST(
      new Request("http://localhost:3000/api/copilotkit/invest/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({}),
      }),
    );
    expect(httpAgentConfigs[0].headers).toBeUndefined();
  });

  it("resume 轮（空 messages + resume）原样透传，不被消息裁剪破坏", async () => {
    // agentscope 2.0.3 权限确认 interrupt 的续跑请求：server-side-memory 下 messages 为空、
    // 应答挂在顶层 resume[]。trimToLatestUserMessage 对 length<=1 直接透传，resume 不经裁剪路径
    await POST(
      new Request("http://localhost:3000/api/copilotkit/invest/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          input: {
            runId: "run-2",
            messages: [],
            resume: [
              { interruptId: "reply-1:tc9", status: "resolved", payload: { approved: true } },
            ],
          },
        }),
      }),
    );
    expect(handlerBodies).toHaveLength(1);
    expect(handlerBodies[0]).toMatchObject({
      input: {
        messages: [],
        resume: [
          { interruptId: "reply-1:tc9", status: "resolved", payload: { approved: true } },
        ],
      },
    });
  });

  it("多消息轮裁剪只动 messages，resume 字段保留", async () => {
    // HttpAgent 常携带全量历史：裁剪保留最新 user 消息时，同请求内的 resume 必须原样保留
    await POST(
      new Request("http://localhost:3000/api/copilotkit/invest/run", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          input: {
            runId: "run-2",
            messages: [
              { id: "u1", role: "user", content: "写一条记录" },
              { id: "a1", role: "assistant", content: "" },
              { id: "u2", role: "user", content: "继续" },
              { id: "a2", role: "assistant", content: "" },
            ],
            resume: [
              { interruptId: "reply-1:tc9", status: "resolved", payload: { approved: false } },
            ],
          },
        }),
      }),
    );
    expect(handlerBodies[0]).toMatchObject({
      input: {
        messages: [{ id: "u2", role: "user", content: "继续" }],
        resume: [
          { interruptId: "reply-1:tc9", status: "resolved", payload: { approved: false } },
        ],
      },
    });
  });
});
