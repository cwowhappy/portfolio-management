import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { adminApi, estimateCostCny, UNIT_PRICE_CNY_PER_MTOK } from "@/lib/adminApi";

function jsonResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

function noContentResponse(): Response {
  return {
    ok: true,
    status: 204,
    json: vi.fn().mockResolvedValue(undefined),
  } as unknown as Response;
}

// —— fixture 形态抄录自后端 Task 7 切片测试真实响应（EvalTriggerControllerTest.runRow /
// ObservabilityControllerTest.traceRow/cost/latency/prompt-assets），沿「fixture 失真」教训 ——

const completedRun = {
  id: 7,
  triggeredBy: "SCHEDULED",
  status: "COMPLETED",
  startedAt: "2026-10-10T02:17:00Z",
  finishedAt: "2026-10-10T03:25:00Z",
  totalPass: 18,
  totalFail: 2,
  totalError: 0,
  byCategory: { MARKET_FACT: [10, 0, 0] },
  promptVersions: { "system.invest": 3 },
  questionBankHash: "qb-hash",
  alertStatus: "NONE",
  baseline: true,
  baselineCandidate: false,
  verdictReasons: [],
  durationMs: 4_080_000,
  reportPath: "/data/eval-report-7.json",
};

// RUNNING 行形态：触发先插行，收割列全空（finishedAt/questionBankHash/durationMs null）
const runningRun = {
  id: 6,
  triggeredBy: "MANUAL",
  status: "RUNNING",
  startedAt: "2026-10-10T03:30:00Z",
  finishedAt: null,
  totalPass: 0,
  totalFail: 0,
  totalError: 0,
  byCategory: {},
  promptVersions: {},
  questionBankHash: null,
  alertStatus: "NONE",
  baseline: false,
  baselineCandidate: false,
  verdictReasons: [],
  durationMs: null,
  reportPath: "/data/eval-report-6.json",
};

const traceItem = {
  id: 3,
  userId: 2,
  conversationId: "conv-1",
  messageId: "msg-3",
  toolName: "get_quote",
  args: '{"code":"600519"}',
  resultText: "结果文本",
  specCount: 1,
  asOf: "2026-10-09",
  asOfKind: "TRADE_DATE",
  mcp: false,
  failed: true,
  durationMs: 120,
  calledAt: "2026-10-09T09:31:00Z",
};

const costAgg = {
  byDay: [
    { date: "2026-10-09", promptTokens: 150, completionTokens: 60, totalTokens: 210, turns: 2 },
  ],
  byTool: [
    { tool: "get_quote", calls: 3, avgDurationMs: 200.0 },
    { tool: "get_kline", calls: 1, avgDurationMs: null },
  ],
};

const latencyAgg = {
  turn: {
    p50Ms: 300.0,
    p95Ms: 480.0,
    byDay: [{ date: "2026-10-09", p50Ms: 300.0, p95Ms: 480.0, turns: 5 }],
  },
  tool: { byTool: [{ tool: "get_quote", p50Ms: 200.0, p95Ms: 290.0, calls: 3 }] },
};

const promptAssets = {
  assets: [
    {
      assetType: "SKILL",
      assetKey: "skill.tushare_data",
      versions: [
        {
          id: 9,
          version: 2,
          contentHash: "hash-b",
          note: null,
          registeredAt: "2026-10-09T02:00:00Z",
          current: true,
        },
        {
          id: 4,
          version: 1,
          contentHash: "hash-a",
          note: "初版",
          registeredAt: "2026-10-01T02:00:00Z",
          current: false,
        },
      ],
    },
  ],
};

describe("管理员 REST 客户端：评测与可观测性（MS-30 F1）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("listEvalRuns() GET 裸数组（非信封）且解析 COMPLETED 与 RUNNING 两态形态", async () => {
    fetchMock.mockResolvedValue(jsonResponse([completedRun, runningRun]));
    await expect(adminApi.listEvalRuns()).resolves.toEqual([completedRun, runningRun]);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/eval/runs?limit=20",
      expect.objectContaining({ cache: "no-store", credentials: "same-origin" }),
    );
  });

  it("listEvalRuns(5) 透传 limit", async () => {
    fetchMock.mockResolvedValue(jsonResponse([]));
    await adminApi.listEvalRuns(5);
    expect(fetchMock).toHaveBeenCalledWith("/api/admin/eval/runs?limit=5", expect.anything());
  });

  it("triggerEvalRun() POST 解析 202 {runId}", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ runId: 7 }, 202));
    await expect(adminApi.triggerEvalRun()).resolves.toEqual({ runId: 7 });
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/eval/run",
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("triggerEvalRun() 409（进行中）抛服务端 message", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        { code: "EVAL_RUN_IN_PROGRESS", message: "评测运行进行中（手动与定时互斥），请稍后再试" },
        409,
      ),
    );
    await expect(adminApi.triggerEvalRun()).rejects.toThrow("评测运行进行中（手动与定时互斥），请稍后再试");
  });

  it("triggerEvalRun() 503（未启用/缺 key）抛调度器文案", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          code: "EVAL_TRIGGER_UNAVAILABLE",
          message: "缺少 DEEPSEEK_API_KEY：评测子进程须真实 LLM，请在服务环境变量配置后重启",
        },
        503,
      ),
    );
    await expect(adminApi.triggerEvalRun()).rejects.toThrow("缺少 DEEPSEEK_API_KEY");
  });

  it("setEvalBaseline() PUT 携带 {baseline}，204 无返回", async () => {
    fetchMock.mockResolvedValue(noContentResponse());
    await expect(adminApi.setEvalBaseline(5, true)).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/eval/runs/5/baseline",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ baseline: true }),
        cache: "no-store",
        credentials: "same-origin",
      }),
    );
  });

  it("setEvalBaseline() 422（基准不合格）抛资格规则文案", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse(
        {
          code: "ERR_BASELINE_INELIGIBLE",
          message: "仅 COMPLETED 且非 DEGRADED 跑可置为基准（当前 status=PARTIAL, alert_status=NONE）",
        },
        422,
      ),
    );
    await expect(adminApi.setEvalBaseline(5, true)).rejects.toThrow("仅 COMPLETED 且非 DEGRADED");
  });

  it("setPromptAssetNote() PUT 携带 {note}，204 无返回", async () => {
    fetchMock.mockResolvedValue(noContentResponse());
    await expect(adminApi.setPromptAssetNote(9, "调整引语风格")).resolves.toBeUndefined();
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/prompt-assets/9/note",
      expect.objectContaining({
        method: "PUT",
        body: JSON.stringify({ note: "调整引语风格" }),
        cache: "no-store",
        credentials: "same-origin",
      }),
    );
  });

  it("setPromptAssetNote() 404（版本行不存在）抛服务端 message", async () => {
    fetchMock.mockResolvedValue(
      jsonResponse({ code: "PROMPT_ASSET_NOT_FOUND", message: "提示词资产版本不存在: 99" }, 404),
    );
    await expect(adminApi.setPromptAssetNote(99, "x")).rejects.toThrow("提示词资产版本不存在: 99");
  });

  it("fetchTrace() 缺省请求裸路径；全筛选透传 page/size/from/to/tool/failed", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse({ items: [], page: 0, total: 0 }));
    await adminApi.fetchTrace();
    expect(fetchMock.mock.calls[0][0]).toBe("/api/admin/observability/trace");

    fetchMock.mockResolvedValueOnce(jsonResponse({ items: [traceItem], page: 2, total: 5 }));
    await adminApi.fetchTrace({
      page: 2,
      size: 20,
      from: "2026-10-08T00:00:00Z",
      to: "2026-10-10T00:00:00Z",
      tool: "get_quote",
      failed: true,
    });
    expect(fetchMock.mock.calls[1][0]).toBe(
      "/api/admin/observability/trace?page=2&size=20&from=2026-10-08T00%3A00%3A00Z"
        + "&to=2026-10-10T00%3A00%3A00Z&tool=get_quote&failed=true",
    );
  });

  it("fetchTrace() 解析 {items,page,total} 形态（args 为 JSONB 原文串）", async () => {
    fetchMock.mockResolvedValue(jsonResponse({ items: [traceItem], page: 0, total: 5 }));
    await expect(adminApi.fetchTrace({ page: 0, size: 50 })).resolves.toEqual({
      items: [traceItem],
      page: 0,
      total: 5,
    });
  });

  it("fetchCost()/fetchLatency() days 透传且解析 byDay/byTool 与 turn/tool 形态", async () => {
    fetchMock.mockResolvedValueOnce(jsonResponse(costAgg));
    await expect(adminApi.fetchCost()).resolves.toEqual(costAgg);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/admin/observability/cost?days=7");

    fetchMock.mockResolvedValueOnce(jsonResponse(latencyAgg));
    await expect(adminApi.fetchLatency(30)).resolves.toEqual(latencyAgg);
    expect(fetchMock.mock.calls[1][0]).toBe("/api/admin/observability/latency?days=30");
  });

  it("fetchPromptAssets() 解析版本链（版本行含 id——补注 PUT 定位键；note 可 null）", async () => {
    fetchMock.mockResolvedValue(jsonResponse(promptAssets));
    await expect(adminApi.fetchPromptAssets()).resolves.toEqual(promptAssets);
    expect(fetchMock).toHaveBeenCalledWith(
      "/api/admin/prompt-assets",
      expect.objectContaining({ cache: "no-store", credentials: "same-origin" }),
    );
  });

  it("响应形态失真（版本行缺 id / byCategory 非三元组数组）抛数据格式异常", async () => {
    fetchMock.mockResolvedValueOnce(
      jsonResponse({
        assets: [{ assetType: "SKILL", assetKey: "k", versions: [{ version: 1 }] }],
      }),
    );
    await expect(adminApi.fetchPromptAssets()).rejects.toThrow("数据格式异常");

    fetchMock.mockResolvedValueOnce(
      jsonResponse([{ ...completedRun, byCategory: { MARKET_FACT: "10-0-0" } }]),
    );
    await expect(adminApi.listEvalRuns()).rejects.toThrow("数据格式异常");
  });

  it("单价估算：未配置（0）时 estimateCostCny 返回 null 即不显示", () => {
    expect(UNIT_PRICE_CNY_PER_MTOK).toBe(0);
    expect(estimateCostCny(costAgg)).toBeNull();
  });

  it("单价估算：NEXT_PUBLIC_UNIT_PRICE_CNY_PER_MTOK=2 时按 ΣtotalTokens × 单价 / 1e6", async () => {
    vi.resetModules();
    vi.stubEnv("NEXT_PUBLIC_UNIT_PRICE_CNY_PER_MTOK", "2");
    try {
      const fresh = await import("@/lib/adminApi");
      expect(fresh.UNIT_PRICE_CNY_PER_MTOK).toBe(2);
      // 210 tokens × 2 元/Mtok / 1e6
      expect(fresh.estimateCostCny(costAgg)).toBeCloseTo(0.00042, 12);
    } finally {
      vi.unstubAllEnvs();
    }
  });
});
