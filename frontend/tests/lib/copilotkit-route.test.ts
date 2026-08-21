import { beforeEach, describe, expect, it, vi } from "vitest";

const httpAgentConfigs: Array<Record<string, unknown>> = [];

// 隔离 CopilotKit 运行时依赖，仅验证路由层行为：
// 1) 所有响应追加 no-store（回归 4c8c4545）
// 2) 透传入站 Cookie 给后端 HttpAgent（回归：/agui/run 需要会话，cookie 丢失会 401）
vi.mock("@copilotkit/runtime/v2", () => ({
  CopilotRuntime: class {},
  createCopilotRuntimeHandler: () => async () => new Response("ok", { status: 200 }),
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
});
