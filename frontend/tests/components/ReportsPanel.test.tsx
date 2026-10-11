import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ReportsPanel from "@/components/research/ReportsPanel";
import * as reportApi from "@/lib/reportApi";
import type {
  ReportDetailView,
  ReportSummary,
  ReportTaskStatus,
} from "@/lib/reportSchemas";

// 研报面板（MS-32 F2，设计规格 §4.1）组件层真实渲染断 DOM；仅 mock api 边界函数
// （照 EntryPlanPanel.test 先例）——轮询状态机、默认展开、确认接线不 mock。
// fixture 抄录 B13 MockMvc 真实响应形态（经验 #9，与 tests/reportApi.test.ts 同源）。

vi.mock("@/lib/reportApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/reportApi")>("@/lib/reportApi");
  return {
    ...actual,
    startReportTask: vi.fn(),
    latestTask: vi.fn(),
    listReports: vi.fn(),
    getReport: vi.fn(),
    confirmReport: vi.fn(),
  };
});

const api = vi.mocked(reportApi);

const runningTask: ReportTaskStatus = {
  status: "RUNNING",
  stage: "ANALYZING",
  tokenUsage: 4321,
  errorCode: null,
  errorMessage: null,
  reportId: null,
};

const succeededTask: ReportTaskStatus = {
  status: "SUCCEEDED",
  stage: "VALIDATING",
  tokenUsage: 4321,
  errorCode: null,
  errorMessage: null,
  reportId: 9,
};

const failedTask: ReportTaskStatus = {
  status: "FAILED",
  stage: "ANALYZING",
  tokenUsage: 1200,
  errorCode: "AGENT_ERROR",
  errorMessage: "执行队列已满，请稍后重试",
  reportId: null,
};

const pendingReports: ReportSummary[] = [
  { id: 9, title: "贵州茅台深度研报：三重假设与验证", status: "PENDING_CONFIRM", createdAt: "2026-10-11T02:03:04Z" },
  { id: 3, title: "贵州茅台研报（旧版）", status: "REJECTED", createdAt: "2026-09-28T02:03:04Z" },
];

const confirmedReports: ReportSummary[] = [
  { id: 9, title: "贵州茅台深度研报：三重假设与验证", status: "CONFIRMED", createdAt: "2026-10-11T02:03:04Z" },
  { id: 3, title: "贵州茅台研报（旧版）", status: "REJECTED", createdAt: "2026-09-28T02:03:04Z" },
];

// B13 §研报详情：FRESH（现值字符串）/ STALE（currentValue null）/ anchor null（未绑定兜底）
const pendingDetail: ReportDetailView = {
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

const confirmedDetail: ReportDetailView = { ...pendingDetail, status: "CONFIRMED" };

const oldRejectedDetail: ReportDetailView = {
  id: 3,
  projectId: 5,
  title: "贵州茅台研报（旧版）",
  status: "REJECTED",
  contentMd: "# 旧版研报\n\n旧版正文……",
  limitations: null,
  createdAt: "2026-09-28T02:03:04Z",
  stalenessCheckedAt: "2026-09-28T06:03:04Z",
  assumptions: [],
};

/** fake-timers 下冲洗微任务链（轮询→状态→二级加载→渲染）。 */
const flush = async (n = 6) => {
  for (let i = 0; i < n; i++) await act(async () => {});
};

const stubConfirm = (v: boolean) => vi.spyOn(window, "confirm").mockReturnValue(v);

beforeEach(() => {
  vi.resetAllMocks();
  api.latestTask.mockRejectedValue(new Error("研报任务不存在")); // 无任务初始态（404 语义）
  api.listReports.mockResolvedValue([]);
  api.getReport.mockResolvedValue(pendingDetail);
  api.startReportTask.mockResolvedValue({ taskId: 7, quotaRemaining: 86000 });
  api.confirmReport.mockResolvedValue(confirmedReports[0]);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe("ReportsPanel 发起与错误行内", () => {
  it("初始无任务（latest 404「研报任务不存在」）：静默空态，无错误行内，可发起", async () => {
    render(<ReportsPanel projectId={5} />);
    expect(await screen.findByTestId("reports-empty")).toBeTruthy();
    expect(screen.queryAllByRole("alert")).toHaveLength(0);
    expect((screen.getByTestId("report-generate-button") as HTMLButtonElement).disabled).toBe(false);
  });

  it("发起：window.confirm 二次确认 → 202 → 受理提示 + 配额余量提示条 + 立即拉取任务", async () => {
    stubConfirm(true);
    render(<ReportsPanel projectId={5} />);
    await flush(2);
    fireEvent.click(screen.getByTestId("report-generate-button"));
    await waitFor(() => expect(api.startReportTask).toHaveBeenCalledWith(5));
    expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining("确认发起深度研报"));
    expect(await screen.findByText(/已受理研报任务 #7/)).toBeTruthy();
    expect(await screen.findByText("今日剩余研报算力 86000 token")).toBeTruthy();
    await waitFor(() => expect(api.latestTask).toHaveBeenCalledTimes(2)); // 挂载 1 + 发起后 1
  });

  it("confirm 取消：不发发起请求", async () => {
    stubConfirm(false);
    render(<ReportsPanel projectId={5} />);
    await flush(2);
    fireEvent.click(screen.getByTestId("report-generate-button"));
    await flush(2);
    expect(api.startReportTask).not.toHaveBeenCalled();
  });

  it("402 配额：行内固定文案「今日研报算力已用完，明日恢复」，按钮恢复可点", async () => {
    stubConfirm(true);
    api.startReportTask.mockRejectedValue(
      new Error("当日研报 token 配额已用尽（used=500000，budget=500000）"),
    );
    render(<ReportsPanel projectId={5} />);
    await flush(2);
    fireEvent.click(screen.getByTestId("report-generate-button"));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toBe("今日研报算力已用完，明日恢复");
    expect((screen.getByTestId("report-generate-button") as HTMLButtonElement).disabled).toBe(false);
  });

  it("409 活跃任务：行内固定文案「当前项目已有进行中的研报任务」", async () => {
    stubConfirm(true);
    api.startReportTask.mockRejectedValue(
      new Error("该项目已有进行中的研报任务，请待其完成或失败后重试"),
    );
    render(<ReportsPanel projectId={5} />);
    await flush(2);
    fireEvent.click(screen.getByTestId("report-generate-button"));
    const alert = await screen.findByRole("alert");
    expect(alert.textContent).toBe("当前项目已有进行中的研报任务");
  });

  it("其他发起错误：透传原文（不误配 402/409 文案）", async () => {
    stubConfirm(true);
    api.startReportTask.mockRejectedValue(new Error("项目不存在"));
    render(<ReportsPanel projectId={5} />);
    await flush(2);
    fireEvent.click(screen.getByTestId("report-generate-button"));
    expect(await screen.findByText("项目不存在")).toBeTruthy();
  });

  it("latestTask 非 404 加载错误：行内展示不吞", async () => {
    api.latestTask.mockRejectedValue(new Error("网络中断"));
    render(<ReportsPanel projectId={5} />);
    expect(await screen.findByText("网络中断")).toBeTruthy();
  });

  it("研报列表加载失败：行内展示", async () => {
    api.listReports.mockRejectedValue(new Error("研报列表暂不可用"));
    render(<ReportsPanel projectId={5} />);
    expect(await screen.findByText("研报列表暂不可用")).toBeTruthy();
  });
});

describe("ReportsPanel 任务进度与 5s 轮询（fake timers）", () => {
  it("RUNNING：stage 文案 + 已耗 token 展示，生成按钮禁用，每 5s 轮询一次", async () => {
    vi.useFakeTimers();
    api.latestTask.mockResolvedValue(runningTask);
    render(<ReportsPanel projectId={5} />);
    await flush(2);
    expect(api.latestTask).toHaveBeenCalledTimes(1);
    expect(screen.getByText("分析中")).toBeTruthy();
    expect(screen.getByText("已耗 token 4321")).toBeTruthy();
    expect((screen.getByTestId("report-generate-button") as HTMLButtonElement).disabled).toBe(true);

    await act(async () => {
      vi.advanceTimersByTime(5000);
    });
    expect(api.latestTask).toHaveBeenCalledTimes(2);
  });

  it("终态停轮询：RUNNING→SUCCEEDED 后刷新列表、自动展开新研报、不再轮询", async () => {
    vi.useFakeTimers();
    api.latestTask.mockResolvedValueOnce(runningTask).mockResolvedValue(succeededTask);
    api.listReports.mockResolvedValueOnce([]).mockResolvedValue(pendingReports);
    render(<ReportsPanel projectId={5} />);
    await flush(2);
    expect(api.latestTask).toHaveBeenCalledTimes(1);

    await act(async () => {
      vi.advanceTimersByTime(5000);
    });
    expect(api.latestTask).toHaveBeenCalledTimes(2);
    await flush(3);
    expect(api.listReports).toHaveBeenCalledTimes(2); // 挂载 1 + 成功收割 1
    expect(api.getReport).toHaveBeenCalledWith(9);
    expect(screen.getByTestId("report-detail-9")).toBeTruthy();

    await act(async () => {
      vi.advanceTimersByTime(15000);
    });
    expect(api.latestTask).toHaveBeenCalledTimes(2); // 终态停
  });

  it("卸载即清理定时器", async () => {
    vi.useFakeTimers();
    api.latestTask.mockResolvedValue(runningTask);
    const { unmount } = render(<ReportsPanel projectId={5} />);
    await flush(2);
    expect(api.latestTask).toHaveBeenCalledTimes(1);
    unmount();
    await act(async () => {
      vi.advanceTimersByTime(15000);
    });
    expect(api.latestTask).toHaveBeenCalledTimes(1);
  });

  it("FAILED：错误诊断行内展示，生成按钮恢复可点", async () => {
    api.latestTask.mockResolvedValue(failedTask);
    render(<ReportsPanel projectId={5} />);
    expect(await screen.findByText("生成失败：执行队列已满，请稍后重试")).toBeTruthy();
    expect((screen.getByTestId("report-generate-button") as HTMLButtonElement).disabled).toBe(false);
  });
});

describe("ReportsPanel 列表与详情（ReportDetail 接线）", () => {
  it("列表倒序渲染：最新在前，状态 chip + 日期", async () => {
    api.listReports.mockResolvedValue(pendingReports);
    render(<ReportsPanel projectId={5} />);
    await flush(2);
    const ids = screen
      .getAllByTestId(/^report-item-/)
      .map((el) => el.getAttribute("data-testid"));
    expect(ids).toEqual(["report-item-9", "report-item-3"]);
    expect(screen.getByText("待确认")).toBeTruthy();
    expect(screen.getByText("已拒绝")).toBeTruthy();
    expect(screen.getByText("#9 · 2026-10-11")).toBeTruthy();
    expect(screen.getByText("#3 · 2026-09-28")).toBeTruthy();
  });

  it("默认展开最新待确认研报：markdown 正文 + 假设四态徽标 + 局限注记 + 确认卡", async () => {
    api.listReports.mockResolvedValue(pendingReports);
    render(<ReportsPanel projectId={5} />);
    await flush(3);
    expect(api.getReport).toHaveBeenCalledWith(9);
    expect(screen.getByTestId("report-detail-9")).toBeTruthy();
    // MarkdownView 渲染 contentMd
    expect(screen.getByText("正文……")).toBeTruthy();
    // 假设：FRESH/STALE 徽标 + 未绑定兜底 + 重查时点
    const assumptions = screen.getByTestId("report-assumptions");
    expect(within(assumptions).getAllByTestId("report-anchor-badge")).toHaveLength(2);
    expect(within(assumptions).getByText("现值一致")).toBeTruthy();
    expect(within(assumptions).getByText("数据过期")).toBeTruthy();
    expect(within(assumptions).getByText("未绑定锚点")).toBeTruthy();
    expect(screen.getByText(/锚点重查于 2026-10-11/)).toBeTruthy();
    // limitations 注记块
    expect(screen.getByTestId("report-limitations").textContent).toContain("数据截至 2026-10-10 收盘");
    // 确认卡（PENDING_CONFIRM）
    expect(screen.getByRole("button", { name: "确认定稿" })).toBeTruthy();
  });

  it("收起/展开切换：点标题收起；展开旧研报拉取对应详情（终态卡无操作按钮）", async () => {
    api.listReports.mockResolvedValue(pendingReports);
    render(<ReportsPanel projectId={5} />);
    await flush(3);
    fireEvent.click(
      screen.getByRole("button", { name: "收起研报「贵州茅台深度研报：三重假设与验证」" }),
    );
    expect(screen.queryByTestId("report-detail-9")).toBeNull();

    api.getReport.mockResolvedValueOnce(oldRejectedDetail);
    fireEvent.click(screen.getByRole("button", { name: "展开研报「贵州茅台研报（旧版）」" }));
    await waitFor(() => expect(api.getReport).toHaveBeenCalledWith(3));
    expect(await screen.findByTestId("report-detail-3")).toBeTruthy();
    expect(screen.getByText("旧版正文……")).toBeTruthy();
    // 已拒绝终态：确认卡只读展示，无操作按钮
    expect(within(screen.getByTestId("report-confirm-card")).getByText("已拒绝")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "确认定稿" })).toBeNull();
  });

  it("确认接线：确认定稿 → confirmReport(9, CONFIRM) → 列表与详情即时刷新 → 终态已定稿", async () => {
    api.listReports.mockResolvedValueOnce(pendingReports).mockResolvedValue(confirmedReports);
    render(<ReportsPanel projectId={5} />);
    await flush(3);
    api.getReport.mockResolvedValue(confirmedDetail); // 确认后刷新返回终态详情

    fireEvent.click(screen.getByRole("button", { name: "确认定稿" }));
    await waitFor(() => expect(api.confirmReport).toHaveBeenCalledWith(9, "CONFIRM", undefined));
    await waitFor(() => expect(api.listReports).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(api.getReport).toHaveBeenCalledTimes(2));
    await waitFor(() =>
      expect(within(screen.getByTestId("report-confirm-card")).getByText("已定稿")).toBeTruthy(),
    );
    expect(screen.queryByRole("button", { name: "确认定稿" })).toBeNull();
    // 列表 chip 同步终态
    expect(screen.getByTestId("report-item-9").textContent).toContain("已定稿");
  });
});
