import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DELETE, GET, PUT } from "@/app/api/memory/[[...path]]/route";
import type { NextRequest } from "next/server";

function req(url: string, init?: RequestInit): NextRequest {
  return new Request(url, init) as unknown as NextRequest;
}

describe("memory 反代路由", () => {
  const fetchMock = vi.fn();
  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GET 裸路径拼对上游", async () => {
    fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
    await GET(req("http://localhost:3000/api/memory"), { params: Promise.resolve({ path: [] }) });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/memory");
  });

  it("GET /profile-config 段拼接", async () => {
    fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
    await GET(req("http://localhost:3000/api/memory/profile-config"), {
      params: Promise.resolve({ path: ["profile-config"] }),
    });
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/memory/profile-config");
  });

  it("DELETE 透传 body 到 /curated（乐观并发入参在请求体）", async () => {
    fetchMock.mockResolvedValue(new Response('{"purgedLedgerLines":2}', { status: 200 }));
    const body = '{"lineIndex":2,"expectLine":"- 偏好白酒股","baseHash":"a1b2c3d4"}';
    await DELETE(new Request("http://localhost:3000/api/memory/curated", { method: "DELETE", body }), {
      params: Promise.resolve({ path: ["curated"] }),
    });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/memory/curated");
    expect(init.method).toBe("DELETE");
    expect(init.body).toBe(body);
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("DELETE 透传 body 到 /ledger/{date}（date 路径段重编码）", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const body = '{"sectionHeader":"## Memory Flush — 2026-10-09T13:58:45Z","baseHash":"e5f6a7b8"}';
    const res = await DELETE(
      new Request("http://localhost:3000/api/memory/ledger/2026-10-09", { method: "DELETE", body }),
      { params: Promise.resolve({ path: ["ledger", "2026-10-09"] }) },
    );
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/memory/ledger/2026-10-09");
    expect(init.method).toBe("DELETE");
    expect(init.body).toBe(body);
    // 204 无响应体：上游 204 中继后仍为 204（Response 构造不允许携带 body）
    expect(res.status).toBe(204);
    expect(await res.text()).toBe("");
  });

  it("PUT 透传 body 到 /curated（编辑行四字段）", async () => {
    fetchMock.mockResolvedValue(new Response(null, { status: 204 }));
    const body = '{"lineIndex":2,"expectLine":"- 偏好白酒股","newLine":"- 偏好新能源","baseHash":"a1b2c3d4"}';
    await PUT(new Request("http://localhost:3000/api/memory/curated", { method: "PUT", body }), {
      params: Promise.resolve({ path: ["curated"] }),
    });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("http://localhost:8080/api/memory/curated");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe(body);
  });

  it("`..` 路径段拒绝 400", async () => {
    const res = await GET(req("http://localhost:3000/api/memory/..%2Fadmin"), {
      params: Promise.resolve({ path: ["..", "admin"] }),
    });
    expect(res.status).toBe(400);
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it("透传入站 Cookie 到上游", async () => {
    fetchMock.mockResolvedValue(new Response("{}", { status: 200 }));
    await GET(new Request("http://localhost:3000/api/memory", { headers: { Cookie: "JSESSIONID=abc" } }), {
      params: Promise.resolve({ path: [] }),
    });
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect((init.headers as Record<string, string>).Cookie).toBe("JSESSIONID=abc");
  });
});
