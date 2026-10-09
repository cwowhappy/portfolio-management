import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import ObservabilitySection from "@/components/admin/observability/ObservabilitySection";
import EvalRunsPanel from "@/components/admin/observability/EvalRunsPanel";
import PromptAssetPanel from "@/components/admin/observability/PromptAssetPanel";
import {
  adminApi,
  type CostAgg,
  type EvalRun,
  type LatencyAgg,
  type PromptAssets,
} from "@/lib/adminApi";

// jsdom 无 canvas：mock EChart 壳（区块②③在 Section 壳测试中一并挂载）
vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

vi.mock("@/lib/adminApi", () => ({
  adminApi: {
    fetchTrace: vi.fn(),
    fetchCost: vi.fn(),
    fetchLatency: vi.fn(),
    fetchPromptAssets: vi.fn(),
    listEvalRuns: vi.fn(),
    triggerEvalRun: vi.fn(),
    setEvalBaseline: vi.fn(),
    setPromptAssetNote: vi.fn(),
  },
  estimateCostCny: vi.fn().mockReturnValue(null),
  UNIT_PRICE_CNY_PER_MTOK: 0,
}));

const api = vi.mocked(adminApi);

// —— fixture 形态抄录自 Task 7 切片测试真实响应（EvalTriggerControllerTest.runRow / ObservabilityControllerTest）——
const completedRun = (over: Partial<EvalRun> = {}): EvalRun => ({
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
  verdictReasons: ["总分 85 < 基准 95（降 10.0pp）"],
  durationMs: 4_080_000,
  reportPath: "/data/eval-report-7.json",
  ...over,
});

const runningRun = (over: Partial<EvalRun> = {}): EvalRun => ({
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
  ...over,
});

const promptAssets = (note: string | null): PromptAssets => ({
  assets: [
    {
      assetType: "SKILL",
      assetKey: "skill.tushare_data",
      versions: [
        {
          id: 9,
          version: 2,
          contentHash: "hash-b",
          note,
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
});

const emptyCost: CostAgg = { byDay: [], byTool: [] };
const emptyLatency: LatencyAgg = {
  turn: { p50Ms: null, p95Ms: null, byDay: [] },
  tool: { byTool: [] },
};

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchTrace.mockResolvedValue({ items: [], page: 0, total: 0 });
  api.fetchCost.mockResolvedValue(emptyCost);
  api.fetchLatency.mockResolvedValue(emptyLatency);
  api.fetchPromptAssets.mockResolvedValue({ assets: [] });
  api.listEvalRuns.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe("可观测性与评测 Section（四区块壳）", () => {
  it("挂载即渲染四区块标题：明细/成本/时延/版本与运行历史，子面板自取数", async () => {
    render(<ObservabilitySection />);

    expect(await screen.findByText("工具调用明细")).toBeTruthy();
    expect(screen.getByText("成本看板")).toBeTruthy();
    expect(screen.getByText("时延看板")).toBeTruthy();
    expect(screen.getByText("提示词版本与评测运行")).toBeTruthy();
    expect(api.fetchTrace).toHaveBeenCalledTimes(1);
    expect(api.fetchCost).toHaveBeenCalledTimes(1);
    expect(api.fetchLatency).toHaveBeenCalledTimes(1);
    expect(api.fetchPromptAssets).toHaveBeenCalledTimes(1);
    expect(api.listEvalRuns).toHaveBeenCalledTimes(1);
  });
});

describe("观测区块④a：提示词版本链（PromptAssetPanel）", () => {
  it("渲染版本链：最新版「当前」徽标、未注记版本带徽标、已注记显示说明文本", async () => {
    api.fetchPromptAssets.mockResolvedValue(promptAssets(null));

    render(<PromptAssetPanel />);

    expect(await screen.findByText("skill.tushare_data")).toBeTruthy();
    expect(screen.getByText("当前")).toBeTruthy();
    expect(screen.getByText("未注记")).toBeTruthy();
    expect(screen.getByText("初版")).toBeTruthy();
    expect(screen.getAllByText(/v\d/).length).toBe(2);
  });

  it("行内补注：输入说明 → PUT 版本行 id → 刷新后「未注记」徽标消失、说明呈现", async () => {
    api.fetchPromptAssets
      .mockResolvedValueOnce(promptAssets(null))
      .mockResolvedValueOnce(promptAssets("调整引语风格"));
    api.setPromptAssetNote.mockResolvedValue(undefined);

    render(<PromptAssetPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "补注" }));

    const input = screen.getByLabelText("版本说明");
    fireEvent.change(input, { target: { value: "调整引语风格" } });
    fireEvent.click(screen.getByRole("button", { name: "保存" }));

    await waitFor(() =>
      expect(api.setPromptAssetNote).toHaveBeenCalledWith(9, "调整引语风格"),
    );
    await screen.findByText("调整引语风格");
    expect(screen.queryByText("未注记")).toBeNull(); // 徽标消失
  });

  it("补注失败显示服务端错误文案；空说明不发起请求", async () => {
    api.fetchPromptAssets.mockResolvedValue(promptAssets(null));

    render(<PromptAssetPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "补注" }));
    // 空说明：保存禁用（后端 @NotBlank 同源约束）
    expect((screen.getByRole("button", { name: "保存" }) as HTMLButtonElement).disabled).toBe(
      true,
    );

    fireEvent.change(screen.getByLabelText("版本说明"), { target: { value: "x" } });
    api.setPromptAssetNote.mockRejectedValueOnce(new Error("提示词资产版本不存在: 99"));
    fireEvent.click(screen.getByRole("button", { name: "保存" }));
    expect(await screen.findByText(/提示词资产版本不存在/)).toBeTruthy();
  });

  it("空态：无版本登记时渲染引导文案", async () => {
    api.fetchPromptAssets.mockResolvedValue({ assets: [] });

    render(<PromptAssetPanel />);

    const empty = await screen.findByTestId("prompt-assets-empty");
    expect(empty.textContent).toContain("暂无提示词版本登记");
  });
});

describe("观测区块④b：评测运行历史（EvalRunsPanel）", () => {
  it("渲染历史行：状态/触发方式/得分/告警/基准星标/时长；分类明细悬停可见", async () => {
    api.listEvalRuns.mockResolvedValue([
      completedRun(),
      runningRun({ baselineCandidate: true }),
    ]);

    render(<EvalRunsPanel />);

    expect(await screen.findByText("完成")).toBeTruthy();
    expect(screen.getByText("进行中")).toBeTruthy();
    expect(screen.getByText("定时")).toBeTruthy();
    expect(screen.getByText("手动")).toBeTruthy();
    expect(screen.getAllByText("正常")).toHaveLength(2); // 两行 alertStatus=NONE
    expect(screen.getByText("恢复候选")).toBeTruthy(); // baselineCandidate 徽标
    expect(screen.getByTitle(/MARKET_FACT：通过 10 \/ 失败 0 \/ 异常 0/)).toBeTruthy();
    expect(screen.getByTitle(/总分 85 < 基准 95/)).toBeTruthy(); // verdictReasons 悬停
    expect(screen.getByRole("button", { name: "取消基准（运行 7）" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "置为基准（运行 6）" })).toBeTruthy();
  });

  it("触发评测：POST 受理后提示 runId 并刷新历史", async () => {
    api.listEvalRuns.mockResolvedValue([]);
    api.triggerEvalRun.mockResolvedValue({ runId: 8 });

    render(<EvalRunsPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "触发评测" }));

    await waitFor(() => expect(api.triggerEvalRun).toHaveBeenCalledTimes(1));
    expect(await screen.findByText(/已受理评测运行 #8/)).toBeTruthy();
    await waitFor(() => expect(api.listEvalRuns).toHaveBeenCalledTimes(2));
  });

  it("触发评测 409（进行中）：直出服务端文案", async () => {
    api.triggerEvalRun.mockRejectedValue(
      new Error("评测运行进行中（手动与定时互斥），请稍后再试"),
    );

    render(<EvalRunsPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "触发评测" }));

    expect(
      await screen.findByText(/评测运行进行中（手动与定时互斥），请稍后再试/),
    ).toBeTruthy();
  });

  it("基准星标切换：点击非基准行 PUT {baseline:true} 后刷新；422 透出资格文案", async () => {
    api.listEvalRuns.mockResolvedValue([completedRun({ baseline: false })]);
    api.setEvalBaseline.mockResolvedValue(undefined);

    render(<EvalRunsPanel />);
    fireEvent.click(await screen.findByRole("button", { name: "置为基准（运行 7）" }));

    await waitFor(() => expect(api.setEvalBaseline).toHaveBeenCalledWith(7, true));
    await waitFor(() => expect(api.listEvalRuns).toHaveBeenCalledTimes(2));

    api.setEvalBaseline.mockRejectedValueOnce(
      new Error("仅 COMPLETED 且非 DEGRADED 跑可置为基准（当前 status=PARTIAL, alert_status=NONE）"),
    );
    fireEvent.click(screen.getByRole("button", { name: "置为基准（运行 7）" }));
    expect(await screen.findByText(/仅 COMPLETED 且非 DEGRADED 跑可置为基准/)).toBeTruthy();
  });

  it("RUNNING 轮询：最新行 RUNNING 时每 5s 刷新，离开 RUNNING 后停止", async () => {
    vi.useFakeTimers();
    api.listEvalRuns.mockResolvedValue([runningRun()]);

    render(<EvalRunsPanel />);
    await act(async () => {});
    expect(api.listEvalRuns).toHaveBeenCalledTimes(1);
    expect(screen.getByText("进行中")).toBeTruthy();

    await act(async () => {
      vi.advanceTimersByTime(5000);
    });
    expect(api.listEvalRuns).toHaveBeenCalledTimes(2);

    // 收割完成 → 离开 RUNNING → 轮询停止
    api.listEvalRuns.mockResolvedValue([completedRun()]);
    await act(async () => {
      vi.advanceTimersByTime(5000);
    });
    expect(api.listEvalRuns).toHaveBeenCalledTimes(3);
    await act(async () => {
      vi.advanceTimersByTime(15_000);
    });
    expect(api.listEvalRuns).toHaveBeenCalledTimes(3); // 不再轮询
  });

  it("RUNNING 轮询：卸载即清理定时器", async () => {
    vi.useFakeTimers();
    api.listEvalRuns.mockResolvedValue([runningRun()]);

    const { unmount } = render(<EvalRunsPanel />);
    await act(async () => {});
    expect(api.listEvalRuns).toHaveBeenCalledTimes(1);

    unmount();
    await act(async () => {
      vi.advanceTimersByTime(15_000);
    });
    expect(api.listEvalRuns).toHaveBeenCalledTimes(1); // 卸载后不再触发
  });

  it("空态：无运行历史时渲染引导文案", async () => {
    api.listEvalRuns.mockResolvedValue([]);

    render(<EvalRunsPanel />);

    const empty = await screen.findByTestId("eval-runs-empty");
    expect(empty.textContent).toContain("暂无评测运行");
  });
});
