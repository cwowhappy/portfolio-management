import { afterEach, describe, it, expect, vi } from "vitest";
import {
  createRule, createWikiEntry, fetchRules, fetchWikiEntries, PRINCIPLE_METRIC_LABELS, RATIO_METRICS,
} from "@/lib/wikiApi";

const entryJson = {
  id: 5, type: "BOOK_NOTE", title: "《聪明的投资者》", content: "## 核心观点",
  category: null, industryCode: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z",
};
const ruleJson = {
  id: 9, metric: "SINGLE_POSITION_RATIO", threshold: 0.2, enabled: true,
  description: null, createdAt: "2026-09-22T08:00:00Z", updatedAt: "2026-09-22T08:00:00Z",
};

afterEach(() => { vi.unstubAllGlobals(); });

describe("wikiApi", () => {
  it("fetchWikiEntries 解析条目列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [entryJson] }));
    const data = await fetchWikiEntries();
    expect(data[0].title).toBe("《聪明的投资者》");
    expect(fetchMockCall()[0]).toBe("/api/wiki/entries");
  });

  it("fetchWikiEntries 带类型过滤拼 query", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] }));
    await fetchWikiEntries("CONCEPT");
    expect(fetchMockCall()[0]).toBe("/api/wiki/entries?type=CONCEPT");
  });

  it("createWikiEntry 走 POST 并解析", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => entryJson });
    vi.stubGlobal("fetch", fetchMock);
    const entry = await createWikiEntry({ type: "BOOK_NOTE", title: "《聪明的投资者》", content: "## 核心观点" });
    expect(entry.id).toBe(5);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/wiki/entries");
    expect(init.method).toBe("POST");
  });

  it("fetchRules 解析规则列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [ruleJson] }));
    const rules = await fetchRules();
    expect(rules[0].metric).toBe("SINGLE_POSITION_RATIO");
    expect(rules[0].threshold).toBe(0.2);
  });

  it("createRule 走 POST 并解析", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => ruleJson });
    vi.stubGlobal("fetch", fetchMock);
    const rule = await createRule({ metric: "SINGLE_POSITION_RATIO", threshold: 0.2, enabled: true });
    expect(rule.id).toBe(9);
    expect(fetchMock.mock.calls[0][1].method).toBe("POST");
  });

  it("响应不符合 schema 时抛校验错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [{ metric: "UNKNOWN" }] }));
    await expect(fetchRules()).rejects.toThrow();
  });

  it("标签与比例指标集合常量", () => {
    expect(PRINCIPLE_METRIC_LABELS.SINGLE_POSITION_RATIO).toBe("单票仓位上限");
    expect(RATIO_METRICS.has("SINGLE_POSITION_RATIO")).toBe(true);
    expect(RATIO_METRICS.has("STOCK_PE_MAX")).toBe(false);
  });
});

function fetchMockCall(): [string, RequestInit] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
}
