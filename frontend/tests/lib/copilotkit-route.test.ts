import { describe, expect, it, vi } from "vitest";

// 隔离 CopilotKit 运行时依赖，仅验证路由层给所有响应追加 no-store（回归 4c8c4545）。
vi.mock("@copilotkit/runtime/v2", () => ({
  CopilotRuntime: class {},
  createCopilotRuntimeHandler: () => async () => new Response("ok", { status: 200 }),
}));
vi.mock("@ag-ui/client", () => ({
  HttpAgent: class {},
}));

import { GET } from "@/app/api/copilotkit/[[...slug]]/route";

describe("CopilotKit 反代路由", () => {
  it("所有响应都带 Cache-Control: no-store", async () => {
    const res = await GET(new Request("http://localhost:3000/api/copilotkit/info"));
    expect(res.headers.get("Cache-Control")).toBe("no-store, no-cache, must-revalidate");
  });
});
