import { afterEach, describe, expect, it, vi } from "vitest";
import {
  AnchorStateSchema,
  ReportDetailViewSchema,
  ReportSummarySchema,
  ReportTaskStatusSchema,
  StartReportTaskResultSchema,
} from "@/lib/reportSchemas";
import {
  confirmReport,
  fetchLatestTask,
  fetchReportDetail,
  fetchReports,
  startReportTask,
} from "@/lib/reportApi";

// 研报域 api 层（MS-32 深度研究工作流）：URL/方法/请求体逐字对齐后端 ReportTaskController 五端点，
// 出参 zod 边界校验。fixture 逐字抄录 B13 MockMvc 真实响应（BOOT_LIKE_MAPPER ISO 串形态），
// 禁止自造（经验 #9）。

const startResultJson = { taskId: 7, quotaRemaining: 86000 };

// B13 §任务轮询（SUCCEEDED 回连 reportId，进行中两者为 null）
const taskStatusJson = {
  status: "SUCCEEDED",
  stage: "VALIDATING",
  tokenUsage: 4321,
  errorCode: null,
  errorMessage: null,
  reportId: 9,
};

// B13 §任务轮询 FAILED 形态（errorCode/errorMessage 透出诊断，reportId null）——
// AGENT_ERROR/文案抄自 B13 报告 fail-closed 路径。
const taskFailedJson = {
  status: "FAILED",
  stage: "ANALYZING",
  tokenUsage: 1200,
  errorCode: "AGENT_ERROR",
  errorMessage: "执行队列已满，请稍后重试",
  reportId: null,
};

// B13 §研报列表（createdAt 倒序）
const reportsJson = [
  { id: 9, title: "贵州茅台深度研报：三重假设与验证", status: "PENDING_CONFIRM", createdAt: "2026-10-11T02:03:04Z" },
  { id: 3, title: "贵州茅台研报（旧版）", status: "REJECTED", createdAt: "2026-09-28T02:03:04Z" },
];

// B13 §研报详情（anchor.state ∈ FRESH/STALE/CHANGED/UNVERIFIABLE，currentValue 不可得为 null）；
// 追加第三条 anchor:null（B13 注明「anchor 为 null 表示未绑定假设」的 wire 形态）。
const reportDetailJson = {
  id: 9,
  projectId: 5,
  title: "贵州茅台深度研报：三重假设与验证",
  status: "PENDING_CONFIRM",
  contentMd: "# 贵州茅台深度研报\n\n正文……",
  limitations: "数据截至 2026-10-10 收盘",
  createdAt: "2026-10-11T02:03:04Z",
  stalenessCheckedAt: "2026-10-11T06:03:04Z",
  assumptions: [
    {
      seq: 1,
      statement: "2026 年白酒行业动销回暖",
      anchor: {
        tool: "get_kline_daily",
        args: { symbol: "600519", period: "daily" },
        asOf: "2026-10-10T07:00:00Z",
        state: "FRESH",
        currentValue: "1580.00",
      },
    },
    {
      seq: 2,
      statement: "批价企稳回升",
      anchor: {
        tool: "get_quote",
        args: { symbol: "600519" },
        asOf: "2026-09-30T07:00:00Z",
        state: "STALE",
        currentValue: null,
      },
    },
    { seq: 3, statement: "产能扩建按期投产", anchor: null },
  ],
};

// B13 §确认响应
const confirmedSummaryJson = {
  id: 9,
  title: "贵州茅台深度研报：三重假设与验证",
  status: "CONFIRMED",
  createdAt: "2026-10-11T02:03:04Z",
};

afterEach(() => {
  vi.unstubAllGlobals();
});

function fetchMockCall(index = 0): [string, RequestInit] {
  return (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls[index] as [string, RequestInit];
}

describe("reportSchemas round-trip（fixture 抄录 B13 MockMvc 真实响应）", () => {
  it("StartReportTaskResultSchema 解析 202 发起响应 {taskId, quotaRemaining}", () => {
    expect(StartReportTaskResultSchema.parse(startResultJson)).toEqual(startResultJson);
  });

  it("ReportTaskStatusSchema 解析 SUCCEEDED（回连 reportId）/ FAILED（透出诊断）两类", () => {
    const succeeded = ReportTaskStatusSchema.parse(taskStatusJson);
    expect(succeeded.reportId).toBe(9);
    expect(succeeded.errorCode).toBeNull();

    const failed = ReportTaskStatusSchema.parse(taskFailedJson);
    expect(failed.status).toBe("FAILED");
    expect(failed.errorCode).toBe("AGENT_ERROR");
    expect(failed.reportId).toBeNull();
  });

  it("ReportSummarySchema 解析 PENDING_CONFIRM/REJECTED 两行", () => {
    const rows = reportsJson.map((row) => ReportSummarySchema.parse(row));
    expect(rows[0].status).toBe("PENDING_CONFIRM");
    expect(rows[1].status).toBe("REJECTED");
  });

  it("ReportDetailViewSchema 解析详情：anchor FRESH 带字符串 currentValue / STALE 为 null / 未绑定为 null", () => {
    const detail = ReportDetailViewSchema.parse(reportDetailJson);
    expect(detail.assumptions).toHaveLength(3);
    expect(detail.assumptions[0].anchor?.currentValue).toBe("1580.00");
    expect(detail.assumptions[0].anchor?.args).toEqual({ symbol: "600519", period: "daily" });
    expect(detail.assumptions[1].anchor?.state).toBe("STALE");
    expect(detail.assumptions[1].anchor?.currentValue).toBeNull();
    expect(detail.assumptions[2].anchor).toBeNull();
    expect(detail.stalenessCheckedAt).toBe("2026-10-11T06:03:04Z");
  });

  it("limitations 可空（域 create 允许 null），其余必填字段不接受 null", () => {
    const noLimitations = ReportDetailViewSchema.parse({ ...reportDetailJson, limitations: null });
    expect(noLimitations.limitations).toBeNull();

    const bad = ReportDetailViewSchema.safeParse({ ...reportDetailJson, title: null });
    expect(bad.success).toBe(false);
  });

  it("反例：未知任务 status / 锚点 state 枚举外拒收", () => {
    expect(ReportTaskStatusSchema.safeParse({ ...taskStatusJson, status: "CANCELLED" }).success).toBe(false);
    expect(
      ReportTaskStatusSchema.safeParse({ ...taskStatusJson, stage: "SUMMARIZING" }).success,
    ).toBe(false);
    expect(
      ReportDetailViewSchema.safeParse({
        ...reportDetailJson,
        assumptions: [
          { seq: 1, statement: "x", anchor: { ...reportDetailJson.assumptions[0].anchor!, state: "EXPIRED" } },
        ],
      }).success,
    ).toBe(false);
    expect(AnchorStateSchema.safeParse("EXPIRED").success).toBe(false);
    expect(ReportSummarySchema.safeParse({ ...reportsJson[0], status: "DRAFT" }).success).toBe(false);
  });
});

describe("reportApi 五端点（URL/方法/请求体逐字对齐 ReportTaskController）", () => {
  it("startReportTask POST report-tasks 无 body，202 解析 {taskId, quotaRemaining}", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 202, json: async () => startResultJson });
    vi.stubGlobal("fetch", fetchMock);
    const result = await startReportTask(5);
    expect(result).toEqual({ taskId: 7, quotaRemaining: 86000 });
    const [url, init] = fetchMockCall();
    expect(url).toBe("/api/research/projects/5/report-tasks");
    expect(init.method).toBe("POST");
    expect(init.body).toBeUndefined();
  });

  it("startReportTask 402 配额：http 封装透传后端 message（code/remaining 不在错误面）", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false,
      status: 402,
      json: async () => ({
        code: "REPORT_QUOTA_EXCEEDED",
        message: "当日研报 token 配额已用尽，次日恢复",
        remaining: 0,
      }),
    }));
    await expect(startReportTask(5)).rejects.toThrow("当日研报 token 配额已用尽，次日恢复");
  });

  it("startReportTask 409 活跃任务冲突：透传 message", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ code: "TASK_ACTIVE_EXISTS", message: "该项目已有进行中的研报任务" }),
    }));
    await expect(startReportTask(5)).rejects.toThrow("该项目已有进行中的研报任务");
  });

  it("fetchLatestTask GET report-tasks/latest 解析任务状态视图", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => taskStatusJson });
    vi.stubGlobal("fetch", fetchMock);
    const task = await fetchLatestTask(5);
    expect(task.status).toBe("SUCCEEDED");
    expect(task.reportId).toBe(9);
    expect(fetchMockCall()).toEqual([
      "/api/research/projects/5/report-tasks/latest",
      expect.objectContaining({ method: "GET", body: undefined }),
    ]);
  });

  it("fetchReports GET reports 解析摘要列表（倒序两行）", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => reportsJson });
    vi.stubGlobal("fetch", fetchMock);
    const rows = await fetchReports(5);
    expect(rows).toHaveLength(2);
    expect(rows[1].status).toBe("REJECTED");
    expect(fetchMockCall()[0]).toBe("/api/research/projects/5/reports");
    expect(fetchMockCall()[1].method).toBe("GET");
  });

  it("fetchReportDetail GET /api/research/reports/{id} 解析锚点四态详情", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => reportDetailJson });
    vi.stubGlobal("fetch", fetchMock);
    const detail = await fetchReportDetail(9);
    expect(detail.assumptions[0].anchor?.state).toBe("FRESH");
    expect(fetchMockCall()).toEqual([
      "/api/research/reports/9",
      expect.objectContaining({ method: "GET" }),
    ]);
  });

  it("confirmReport CONFIRM：POST body 恰 {action}（不带 reason 键），解析 CONFIRMED 摘要", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => confirmedSummaryJson });
    vi.stubGlobal("fetch", fetchMock);
    const summary = await confirmReport(9, "CONFIRM");
    expect(summary.status).toBe("CONFIRMED");
    const [url, init] = fetchMockCall();
    expect(url).toBe("/api/research/reports/9/confirm");
    expect(init.method).toBe("POST");
    expect(JSON.parse(init.body as string)).toEqual({ action: "CONFIRM" });
  });

  it("confirmReport REJECT 携 reason；confirm 非 2xx（如非待确认态）透传 message", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => confirmedSummaryJson });
    vi.stubGlobal("fetch", fetchMock);
    await confirmReport(9, "REJECT", "假设2批价数据已失效");
    const [, init] = fetchMockCall();
    expect(JSON.parse(init.body as string)).toEqual({
      action: "REJECT",
      reason: "假设2批价数据已失效",
    });

    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false,
      status: 409,
      json: async () => ({ code: "REPORT_INVALID_STATE", message: "研报不在待确认状态（已确认/已拒绝或被并发操作）" }),
    }));
    await expect(confirmReport(9, "CONFIRM")).rejects.toThrow("研报不在待确认状态（已确认/已拒绝或被并发操作）");
  });
});
