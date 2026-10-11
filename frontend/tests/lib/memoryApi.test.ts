import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  deleteCuratedLine,
  deleteLedgerSection,
  editCuratedLine,
  fetchMemory,
  fetchProfileConfig,
  MemoryApiError,
  saveProfileConfig,
} from "@/lib/memoryApi";

function okResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

function noContentResponse(): Response {
  return { ok: true, status: 204 } as unknown as Response;
}

// B3 契约样例：GET /api/memory 全视图（kind 双枚举、hash 字符串形态）
const validMemoryView = {
  curated: {
    hash: "a1b2c3d4",
    lines: ["# MEMORY.md — Curated Long-Term Memory", "## 偏好", "- 偏好白酒股"],
  },
  ledgers: [
    {
      date: "2026-10-09",
      hash: "e5f6a7b8",
      sections: [
        { header: "## Memory Flush — 2026-10-09T13:58:45Z", kind: "FLUSH", lines: ["这是要点"] },
        {
          header: "## 外部事件摘要 — 2026-10-10（a ~ b）",
          kind: "EXTERNAL_EVENT",
          lines: ["- 买入 600519"],
        },
      ],
    },
  ],
};

// B3 契约空态：curated.hash 显式 null（键在）、lines/ledgers 空数组
const emptyMemoryView = {
  curated: { hash: null, lines: [] },
  ledgers: [],
};

// B4 契约样例：preview 段失败为 null（键在）
const validProfileConfig = {
  style: { enabled: true, preview: "## 用户偏好\n- 偏好白酒股" },
  industry: { enabled: false, preview: null },
  watchlist: { enabled: true, preview: null },
  holdings: { enabled: false, preview: null },
};

describe("记忆 REST 客户端（lib/memoryApi）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("fetchMemory 解析完整契约样例（kind 双枚举、hash 字符串）", async () => {
    fetchMock.mockResolvedValue(okResponse(validMemoryView));
    await expect(fetchMemory()).resolves.toEqual(validMemoryView);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/memory");
  });

  it("fetchMemory 空态样例：curated.hash 显式 null（键在）、lines/ledgers 空数组", async () => {
    fetchMock.mockResolvedValue(okResponse(emptyMemoryView));
    await expect(fetchMemory()).resolves.toEqual(emptyMemoryView);
  });

  it("fetchMemory 未知字段剥离（后端多带字段不破坏前端）", async () => {
    fetchMock.mockResolvedValue(
      okResponse({ ...validMemoryView, extra: "unknown" }),
    );
    await expect(fetchMemory()).resolves.toEqual(validMemoryView);
  });

  it("fetchMemory kind 非法枚举值拒绝（数据格式异常）", async () => {
    fetchMock.mockResolvedValue(
      okResponse({
        ...validMemoryView,
        ledgers: [
          {
            date: "2026-10-09",
            hash: "e5f6a7b8",
            sections: [{ header: "## 备注", kind: "NOTE", lines: [] }],
          },
        ],
      }),
    );
    await expect(fetchMemory()).rejects.toThrow("数据格式异常");
  });

  it("fetchMemory curated 缺 hash 键拒绝（空态须为显式 null）", async () => {
    fetchMock.mockResolvedValue(
      okResponse({ curated: { lines: [] }, ledgers: [] }),
    );
    await expect(fetchMemory()).rejects.toThrow("数据格式异常");
  });

  it("fetchProfileConfig 解析契约样例（preview 可 null）", async () => {
    fetchMock.mockResolvedValue(okResponse(validProfileConfig));
    await expect(fetchProfileConfig()).resolves.toEqual(validProfileConfig);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/memory/profile-config");
  });

  it("fetchProfileConfig preview 非字符串非 null 拒绝", async () => {
    fetchMock.mockResolvedValue(
      okResponse({ ...validProfileConfig, style: { enabled: true, preview: 42 } }),
    );
    await expect(fetchProfileConfig()).rejects.toThrow("数据格式异常");
  });

  it("deleteCuratedLine：DELETE /api/memory/curated 带 JSON body，返回清除计数", async () => {
    fetchMock.mockResolvedValue(okResponse({ purgedLedgerLines: 2 }));
    await expect(
      deleteCuratedLine({ lineIndex: 2, expectLine: "- 偏好白酒股", baseHash: "a1b2c3d4" }),
    ).resolves.toEqual({ purgedLedgerLines: 2 });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/memory/curated");
    expect(init.method).toBe("DELETE");
    expect(init.body).toBe(
      JSON.stringify({ lineIndex: 2, expectLine: "- 偏好白酒股", baseHash: "a1b2c3d4" }),
    );
    expect((init.headers as Record<string, string>)["Content-Type"]).toBe("application/json");
  });

  it("editCuratedLine：PUT /api/memory/curated 四字段 body，204 无响应体 resolves undefined", async () => {
    fetchMock.mockResolvedValue(noContentResponse());
    await expect(
      editCuratedLine({
        lineIndex: 2,
        expectLine: "- 偏好白酒股",
        newLine: "- 偏好新能源",
        baseHash: "a1b2c3d4",
      }),
    ).resolves.toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/memory/curated");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual({
      lineIndex: 2,
      expectLine: "- 偏好白酒股",
      newLine: "- 偏好新能源",
      baseHash: "a1b2c3d4",
    });
  });

  it("deleteLedgerSection：DELETE /api/memory/ledger/{date} 带 body，204 resolves undefined", async () => {
    fetchMock.mockResolvedValue(noContentResponse());
    await expect(
      deleteLedgerSection("2026-10-09", {
        sectionHeader: "## Memory Flush — 2026-10-09T13:58:45Z",
        baseHash: "e5f6a7b8",
      }),
    ).resolves.toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/memory/ledger/2026-10-09");
    expect(init.method).toBe("DELETE");
    expect(JSON.parse(init.body as string)).toEqual({
      sectionHeader: "## Memory Flush — 2026-10-09T13:58:45Z",
      baseHash: "e5f6a7b8",
    });
  });

  it("saveProfileConfig：PUT 四开关全量 body，200 回 ProfileConfigView 回执", async () => {
    fetchMock.mockResolvedValue(okResponse(validProfileConfig));
    const saved = { style: true, industry: false, watchlist: true, holdings: false };
    await expect(saveProfileConfig(saved)).resolves.toEqual(validProfileConfig);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/memory/profile-config");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe(JSON.stringify(saved));
  });

  it("409 MEMORY_CONFLICT 抛 MemoryApiError（status/code/message 全保留）", async () => {
    fetchMock.mockResolvedValue(
      okResponse({ code: "MEMORY_CONFLICT", message: "文件已被并发修改" }, 409),
    );
    const err = await fetchMemory().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(MemoryApiError);
    const m = err as MemoryApiError;
    expect(m.status).toBe(409);
    expect(m.code).toBe("MEMORY_CONFLICT");
    expect(m.message).toBe("文件已被并发修改");
  });

  it("410 MEMORY_STALE 抛 MemoryApiError（status=410）", async () => {
    fetchMock.mockResolvedValue(
      okResponse({ code: "MEMORY_STALE", message: "版本已过期" }, 410),
    );
    const err = await fetchMemory().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(MemoryApiError);
    expect((err as MemoryApiError).status).toBe(410);
    expect((err as MemoryApiError).code).toBe("MEMORY_STALE");
  });

  it("400 MEMORY_READ_ONLY 抛 MemoryApiError（status=400，与 INVALID_REQUEST 同分流）", async () => {
    fetchMock.mockResolvedValue(
      okResponse({ code: "MEMORY_READ_ONLY", message: "标题行只读" }, 400),
    );
    const err = await editCuratedLine({
      lineIndex: 0,
      expectLine: "# MEMORY.md — Curated Long-Term Memory",
      newLine: "# 改标题",
      baseHash: "a1b2c3d4",
    }).catch((e: unknown) => e);
    expect(err).toBeInstanceOf(MemoryApiError);
    expect((err as MemoryApiError).status).toBe(400);
    expect((err as MemoryApiError).code).toBe("MEMORY_READ_ONLY");
  });

  it("非 2xx 且错误体非 JSON 时抛默认消息（code=null，status 仍保留）", async () => {
    const bad = {
      ok: false,
      status: 500,
      json: vi.fn().mockRejectedValue(new SyntaxError("Unexpected token")),
    } as unknown as Response;
    fetchMock.mockResolvedValue(bad);
    const err = await fetchMemory().catch((e: unknown) => e);
    expect(err).toBeInstanceOf(MemoryApiError);
    expect((err as MemoryApiError).status).toBe(500);
    expect((err as MemoryApiError).code).toBeNull();
    expect((err as MemoryApiError).message).toBe("请求失败");
  });

  it("所有请求都禁用缓存（cache: no-store）", async () => {
    fetchMock.mockResolvedValue(okResponse(validMemoryView));
    await fetchMemory();
    expect(fetchMock.mock.calls[0][1]).toEqual(
      expect.objectContaining({ cache: "no-store" }),
    );
  });
});
