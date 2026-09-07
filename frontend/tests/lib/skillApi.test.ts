import { afterEach, describe, it, expect, vi } from "vitest";
import { fetchSkills, saveSkillConfig } from "@/lib/skillApi";

const skillJson = {
  skillCode: "tushare_data", description: "使用 Tushare 官方 MCP...", category: "data_source",
  defaultEnabled: false, dependsOnProvider: "tushare", enabled: true,
};

afterEach(() => { vi.unstubAllGlobals(); });

describe("skillApi", () => {
  it("fetchSkills 解析目录列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [skillJson] }));
    const data = await fetchSkills();
    expect(data[0].skillCode).toBe("tushare_data");
    expect(data[0].dependsOnProvider).toBe("tushare");
    expect(fetchMockCall()[0]).toBe("/api/skills");
  });

  it("saveSkillConfig 走 PUT 并透传启用集合", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [skillJson] });
    vi.stubGlobal("fetch", fetchMock);
    const data = await saveSkillConfig(["tushare_data"]);
    expect(data[0].skillCode).toBe("tushare_data");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/skills/config");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe('{"enabled":["tushare_data"]}');
  });

  it("响应缺字段不符合 schema 时抛校验错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [{ skillCode: "x" }] }));
    await expect(fetchSkills()).rejects.toThrow();
  });
});

function fetchMockCall(): [string, RequestInit] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
}
