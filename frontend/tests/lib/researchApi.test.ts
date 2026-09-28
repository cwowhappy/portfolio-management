import { afterEach, describe, it, expect, vi } from "vitest";
import type { NextRequest } from "next/server";
import {
  archiveProject,
  createProject,
  finalizeStrategy,
  getFalsifiers,
  getLinkedNotes,
  getLinkedWiki,
  getProject,
  getStrategy,
  listProjects,
  patchProject,
  reviseStrategy,
  saveFalsifiers,
  saveStrategyDraft,
} from "@/lib/researchApi";
import { GET, PATCH, POST, PUT } from "@/app/api/research/[...path]/route";

// 研究项目域 api 层（invest-sop P2）：URL/方法/请求体逐字对齐后端 ResearchController，
// 出参 zod 边界校验——必填字段拒 null（wire 契约破坏在边界报「数据格式异常」而非深渲染崩溃）。

const projectJson = {
  id: 7,
  stockCode: "600519",
  stockName: "贵州茅台",
  industryCode: "BK0477",
  title: "茅台重启研究",
  currentStage: "NEW_ANALYSIS",
  status: "ACTIVE",
  createdAt: "2026-09-28T08:00:00Z",
  updatedAt: "2026-09-28T08:00:00Z",
};

const strategyJson = {
  id: 3,
  state: "DRAFT",
  thesis: "高端白酒需求刚性",
  valuationLow: 12.5,
  valuationHigh: 18,
  positionPlan: "首仓 10%",
  buyConditions: "PE 低于 20 且放量",
  riskNotes: null,
  finalizedAt: null,
  updatedAt: "2026-09-28T08:00:00Z",
};

const predicateFalsifierJson = {
  id: 11,
  kind: "PREDICATE",
  predicate: "PRICE_BELOW",
  threshold: 12.5,
  eventChecked: false,
  note: "跌破估值下限",
  enabled: true,
};

const eventFalsifierJson = {
  id: 12,
  kind: "EVENT",
  predicate: null,
  threshold: null,
  eventChecked: false,
  note: "食品安全事件",
  enabled: true,
};

const detailJson = {
  project: projectJson,
  completions: {
    NEW_ANALYSIS: { stage: "NEW_ANALYSIS", status: "COMPLETED", basis: "AUTO" },
    STRATEGY: { stage: "STRATEGY", status: "IN_PROGRESS", basis: "PENDING" },
    POSITION: { stage: "POSITION", status: "NOT_STARTED", basis: "PENDING" },
    REVIEW: { stage: "REVIEW", status: "NOT_STARTED", basis: "PENDING" },
  },
  strategy: null,
  falsifiers: [],
};

afterEach(() => {
  vi.unstubAllGlobals();
});

function fetchMockCall(index = 0): [string, RequestInit] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[index] as [string, RequestInit];
}

describe("researchApi", () => {
  it("createProject POST /api/research/projects 带完整立项 body 并解析 ProjectView", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => projectJson });
    vi.stubGlobal("fetch", fetchMock);
    const project = await createProject({
      stockCode: "600519",
      stockName: "贵州茅台",
      industryCode: "BK0477",
      title: "茅台重启研究",
      withTemplate: true,
    });
    expect(project.id).toBe(7);
    expect(project.currentStage).toBe("NEW_ANALYSIS");
    const [url, init] = fetchMockCall();
    expect(url).toBe("/api/research/projects");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({
      stockCode: "600519", stockName: "贵州茅台", industryCode: "BK0477",
      title: "茅台重启研究", withTemplate: true,
    });
  });

  it("listProjects 无过滤不打 query；stage/status/q 组装查询串（q 编码）", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [projectJson] });
    vi.stubGlobal("fetch", fetchMock);
    await listProjects();
    expect(fetchMockCall()[0]).toBe("/api/research/projects");

    await listProjects({ stage: "STRATEGY", status: "ARCHIVED", q: "茅台" });
    expect(fetchMockCall(1)[0]).toBe(
      "/api/research/projects?stage=STRATEGY&status=ARCHIVED&q=%E8%8C%85%E5%8F%B0",
    );
  });

  it("getProject 解析详情读模型（四阶段完成度 + strategy null + falsifiers）", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => detailJson }));
    const detail = await getProject(7);
    expect(fetchMockCall()[0]).toBe("/api/research/projects/7");
    expect(detail.completions.NEW_ANALYSIS).toEqual({
      stage: "NEW_ANALYSIS", status: "COMPLETED", basis: "AUTO",
    });
    expect(detail.strategy).toBeNull();
    expect(detail.falsifiers).toEqual([]);
  });

  it("schema 拒 null：必填字段为 null / 枚举越界 → 抛「数据格式异常」", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ ...detailJson, project: { ...projectJson, title: null } }),
    }));
    await expect(getProject(7)).rejects.toThrow("数据格式异常");

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ ...detailJson, completions: { ...detailJson.completions, STRATEGY: null } }),
    }));
    await expect(getProject(7)).rejects.toThrow("数据格式异常");
  });

  it("patchProject PATCH 带标题/阶段/手动标记整组 body", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => detailJson });
    vi.stubGlobal("fetch", fetchMock);
    await patchProject(7, {
      title: "改名",
      currentStage: "STRATEGY",
      manualMarks: [{ stage: "STRATEGY", state: "COMPLETED" }],
    });
    const [url, init] = fetchMockCall();
    expect(url).toBe("/api/research/projects/7");
    expect(init.method).toBe("PATCH");
    expect(JSON.parse(init.body as string)).toEqual({
      title: "改名",
      currentStage: "STRATEGY",
      manualMarks: [{ stage: "STRATEGY", state: "COMPLETED" }],
    });
  });

  it("archiveProject POST /archive 无 body", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => projectJson });
    vi.stubGlobal("fetch", fetchMock);
    const archived = await archiveProject(7);
    expect(archived.status).toBe("ACTIVE"); // 视图原样解析，状态语义由后端保证
    const [url, init] = fetchMockCall();
    expect(url).toBe("/api/research/projects/7/archive");
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
  });

  it("策略四端点：GET 查询 / PUT 暂存六字段 / finalize / revise 均 POST", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => strategyJson });
    vi.stubGlobal("fetch", fetchMock);

    await getStrategy(7);
    expect(fetchMockCall(0)).toEqual(["/api/research/projects/7/strategy", expect.objectContaining({ method: "GET" })]);

    const draft = await saveStrategyDraft(7, {
      thesis: "需求刚性", valuationLow: 12.5, valuationHigh: 18,
      positionPlan: "首仓 10%", buyConditions: "PE<20", riskNotes: null,
    });
    expect(draft.state).toBe("DRAFT");
    const [putUrl, putInit] = fetchMockCall(1);
    expect(putUrl).toBe("/api/research/projects/7/strategy");
    expect(putInit.method).toBe("PUT");
    expect(JSON.parse(putInit.body as string).valuationLow).toBe(12.5);

    await finalizeStrategy(7);
    expect(fetchMockCall(2)).toEqual([
      "/api/research/projects/7/strategy/finalize",
      expect.objectContaining({ method: "POST" }),
    ]);

    await reviseStrategy(7);
    expect(fetchMockCall(3)).toEqual([
      "/api/research/projects/7/strategy/revise",
      expect.objectContaining({ method: "POST" }),
    ]);
  });

  it("策略端点非 2xx 透传后端 message（如定稿估值倒挂）", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false, status: 422, json: async () => ({ message: "估值下限必须小于上限" }),
    }));
    await expect(finalizeStrategy(7)).rejects.toThrow("估值下限必须小于上限");
  });

  it("证伪条件：GET 解析谓词/事件两类；PUT 整替 body 逐项透传", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => [predicateFalsifierJson, eventFalsifierJson],
    });
    vi.stubGlobal("fetch", fetchMock);
    const items = await getFalsifiers(7);
    expect(items[0].predicate).toBe("PRICE_BELOW");
    expect(items[1].predicate).toBeNull(); // EVENT 类无谓词（可空字段显式 nullable）

    await saveFalsifiers(7, [
      { kind: "PREDICATE", predicate: "PRICE_BELOW", threshold: 12.5, note: "跌破估值下限" },
      { kind: "EVENT", note: "食品安全事件" },
    ]);
    const [url, init] = fetchMockCall(1);
    expect(url).toBe("/api/research/projects/7/falsifiers");
    expect(init.method).toBe("PUT");
    expect(JSON.parse(init.body as string)).toEqual([
      { kind: "PREDICATE", predicate: "PRICE_BELOW", threshold: 12.5, note: "跌破估值下限" },
      { kind: "EVENT", note: "食品安全事件" },
    ]);
  });

  it("F08 反查：getLinkedNotes/getLinkedWiki 走 journal/wiki 列表 ?projectId=", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);
    await getLinkedNotes(7);
    expect(fetchMockCall(0)[0]).toBe("/api/journal/entries?projectId=7");
    await getLinkedWiki(7);
    expect(fetchMockCall(1)[0]).toBe("/api/wiki/entries?projectId=7");
  });

  it("F08 反查带类型叠加：journal 查询串 type 与 projectId 并存", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);
    // 经 journalApi.fetchEntries(type, projectId)——researchApi.getLinkedNotes 不传 type，
    // 此用例锁定底层签名组合行为（供详情页直接复用）
    const { fetchEntries } = await import("@/lib/journalApi");
    await fetchEntries("RESEARCH_EVENT", 7);
    expect(fetchMockCall(0)[0]).toBe("/api/journal/entries?type=RESEARCH_EVENT&projectId=7");
  });
});

describe("research 反代路由", () => {
  const fetchMock = vi.fn();
  const ctx = (path: string[]) => ({ params: Promise.resolve({ path }) });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("GET 拼对上游路径与查询串", async () => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    fetchMock.mockResolvedValue(new Response("[]", { status: 200 }));
    await GET(
      new Request("http://localhost:3000/api/research/projects?stage=STRATEGY") as unknown as NextRequest,
      ctx(["projects"]),
    );
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/research/projects?stage=STRATEGY");
  });

  it("POST/PUT/PATCH 拼路径并透传 body（立项 POST、策略 PUT、项目 PATCH）", async () => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
    // 每次调用给新 Response：relay 会读 body，同一实例二次读取抛 Body unusable
    fetchMock.mockImplementation(async () => new Response("{}", { status: 200 }));

    await POST(
      new Request("http://localhost:3000/api/research/projects", {
        method: "POST",
        body: '{"stockCode":"600519"}',
      }),
      ctx(["projects"]),
    );
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/research/projects");
    expect(fetchMock.mock.calls[0][1].method).toBe("POST");

    await PUT(
      new Request("http://localhost:3000/api/research/projects/7/strategy", {
        method: "PUT",
        body: '{"thesis":"x"}',
      }),
      ctx(["projects", "7", "strategy"]),
    );
    expect(fetchMock.mock.calls[1][0]).toBe("http://localhost:8080/api/research/projects/7/strategy");

    await PATCH(
      new Request("http://localhost:3000/api/research/projects/7", {
        method: "PATCH",
        body: '{"title":"改名"}',
      }),
      ctx(["projects", "7"]),
    );
    expect(fetchMock.mock.calls[2][0]).toBe("http://localhost:8080/api/research/projects/7");
    expect(fetchMock.mock.calls[2][1].method).toBe("PATCH");
    expect(fetchMock.mock.calls[2][1].body).toBe('{"title":"改名"}');
  });
});
