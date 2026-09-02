import { afterEach, describe, it, expect, vi } from "vitest";
import { fetchEntries, fetchTimeline, createEntry, deleteEntry } from "@/lib/journalApi";

const entryJson = {
  id: 5, type: "BUY_MEMO", stockCode: "600519", stockName: "贵州茅台", tradeId: 10,
  title: "买入茅台", content: "理由", targetPrice: 1800, stopLoss: 1400,
  periodType: null, periodStart: null, periodEnd: null,
  eventDate: "2026-09-02", createdAt: "2026-09-02T08:00:00Z", updatedAt: "2026-09-02T08:00:00Z",
};

afterEach(() => { vi.unstubAllGlobals(); });

describe("journalApi", () => {
  it("fetchEntries 解析记录列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [entryJson] }));
    const data = await fetchEntries();
    expect(data[0].title).toBe("买入茅台");
    expect(data[0].stockCode).toBe("600519");
    expect(fetchMockCall()[0]).toBe("/api/journal/entries");
  });

  it("fetchEntries 带类型过滤拼 query", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] }));
    await fetchEntries("REVIEW");
    expect(fetchMockCall()[0]).toBe("/api/journal/entries?type=REVIEW");
  });

  it("fetchTimeline 解析时间线", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => [{ type: "BUY", date: "2026-08-01", title: "贵州茅台", description: "买入 100 股", stockCode: "600519", stockName: "贵州茅台", refId: 10, refType: "TRADE" }],
    }));
    const events = await fetchTimeline();
    expect(events[0].type).toBe("BUY");
    expect(fetchMockCall()[0]).toBe("/api/journal/timeline");
  });

  it("createEntry 走 POST 并解析", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => entryJson });
    vi.stubGlobal("fetch", fetchMock);
    const cmd = { type: "BUY_MEMO" as const, title: "买入茅台", content: "理由", eventDate: "2026-09-02" };
    const entry = await createEntry(cmd);
    expect(entry.id).toBe(5);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/journal/entries");
    expect(init.method).toBe("POST");
  });

  it("响应不符合 schema 时抛校验错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [{ type: "UNKNOWN" }] }));
    await expect(fetchTimeline()).rejects.toThrow();
  });
});

function fetchMockCall(): [string, RequestInit] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[0] as [string, RequestInit];
}
