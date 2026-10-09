import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import CostCharts from "@/components/admin/observability/CostCharts";
import LatencyCharts from "@/components/admin/observability/LatencyCharts";
import { adminApi, estimateCostCny, type CostAgg, type LatencyAgg } from "@/lib/adminApi";

// jsdom 无 canvas：mock EChart 壳，经 data-option 断言传入的 option（沿 AnalyticsBoard 测试模式）
vi.mock("@/components/charts/EChart", () => ({
  EChart: (p: { option: unknown; testid?: string }) => (
    <div data-testid={p.testid ?? "echart"} data-option={JSON.stringify(p.option)} />
  ),
}));

vi.mock("@/lib/adminApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/adminApi")>("@/lib/adminApi");
  return {
    ...actual,
    adminApi: { fetchCost: vi.fn(), fetchLatency: vi.fn() },
    estimateCostCny: vi.fn(),
    // 单价常量走 mock 值（真实 env 解析与估算数学已由 tests/lib/adminApi.eval.test.ts 覆盖）
    UNIT_PRICE_CNY_PER_MTOK: 2,
  };
});

const fetchCost = vi.mocked(adminApi.fetchCost);
const fetchLatency = vi.mocked(adminApi.fetchLatency);
const estimate = vi.mocked(estimateCostCny);

// fixture 形态抄录自 Task 7 切片测试真实响应（ObservabilityControllerTest cost/latency）
const costAgg = (over: Partial<CostAgg> = {}): CostAgg => ({
  byDay: [
    { date: "2026-10-09", promptTokens: 150, completionTokens: 60, totalTokens: 210, turns: 2 },
  ],
  byTool: [
    { tool: "get_quote", calls: 3, avgDurationMs: 200.0 },
    { tool: "get_kline", calls: 1, avgDurationMs: null },
  ],
  ...over,
});

const latencyAgg = (over: Partial<LatencyAgg> = {}): LatencyAgg => ({
  turn: {
    p50Ms: 300.0,
    p95Ms: 480.0,
    byDay: [
      { date: "2026-10-09", p50Ms: 300.0, p95Ms: 480.0, turns: 5 },
      { date: "2026-10-10", p50Ms: 310.0, p95Ms: null, turns: 1 }, // null = 缺口留白
    ],
  },
  tool: { byTool: [{ tool: "get_quote", p50Ms: 200.0, p95Ms: 290.0, calls: 3 }] },
  ...over,
});

/** EChart mock 壳的 option 反序列化形态（断言只用到 xAxis/yAxis/series 三处）。 */
type ChartOptionStub = {
  xAxis?: { data?: string[] };
  yAxis?: { data?: string[] };
  series?: Array<{ name?: string; data?: Array<number | null> }>;
};

function readOption(testid: string): ChartOptionStub {
  return JSON.parse(screen.getByTestId(testid).dataset.option!) as ChartOptionStub;
}

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  cleanup();
});

describe("观测区块②：成本看板（CostCharts）", () => {
  it("按日折线（输入/输出 token）+ 按工具条形（调用次数，均时延入类目标签）", async () => {
    fetchCost.mockResolvedValue(costAgg());

    render(<CostCharts />);

    await screen.findByTestId("cost-tokens-line");
    const line = readOption("cost-tokens-line");
    expect(line.xAxis?.data).toEqual(["2026-10-09"]);
    expect(line.series?.map((s) => s.name)).toEqual(["输入", "输出"]);
    expect(line.series?.[0].data).toEqual([150]);
    expect(line.series?.[1].data).toEqual([60]);

    const bar = readOption("cost-tool-bar");
    expect(bar.series?.[0].data).toEqual([3, 1]);
    // avgDurationMs 有值折算入类目标签（均 200ms），null（全无时长记录）不加后缀
    expect(bar.yAxis?.data).toEqual(["get_quote（均 200ms）", "get_kline"]);
    expect(fetchCost).toHaveBeenCalledWith(7);
  });

  it("窗口切换：近 30 天重新取数", async () => {
    fetchCost.mockResolvedValue(costAgg());

    render(<CostCharts />);
    fireEvent.click(await screen.findByRole("button", { name: "近 30 天" }));

    await waitFor(() => expect(fetchCost).toHaveBeenLastCalledWith(30));
  });

  it("配置单价后角标显示估算金额；未估算（null）不显示角标", async () => {
    estimate.mockReturnValue(0.00042);
    fetchCost.mockResolvedValue(costAgg());

    render(<CostCharts />);
    expect(await screen.findByText(/估算 ¥0\.0004/)).toBeTruthy();
    expect(screen.getByText(/单价 ¥2\/Mtok/)).toBeTruthy();

    estimate.mockReturnValue(null);
    fetchCost.mockResolvedValue(costAgg());
    cleanup();
    render(<CostCharts />);
    await screen.findByTestId("cost-tokens-line");
    expect(screen.queryByText(/估算/)).toBeNull();
  });

  it("空态：byDay/byTool 均空时渲染引导文案「对话产生后自动采集」且不出图", async () => {
    fetchCost.mockResolvedValue(costAgg({ byDay: [], byTool: [] }));

    render(<CostCharts />);

    const empty = await screen.findByTestId("cost-empty");
    expect(empty.textContent).toContain("对话产生后自动采集");
    expect(screen.queryByTestId("cost-tokens-line")).toBeNull();
  });
});

describe("观测区块③：时延看板（LatencyCharts）", () => {
  it("轮整体 p50/p95 指标 + 按日折线（null 缺口留白）+ 按工具 p50/p95 条形", async () => {
    fetchLatency.mockResolvedValue(latencyAgg());

    render(<LatencyCharts />);

    expect(await screen.findByText("300 ms")).toBeTruthy(); // 轮 p50
    expect(screen.getByText("480 ms")).toBeTruthy(); // 轮 p95

    const line = readOption("latency-turn-line");
    expect(line.xAxis?.data).toEqual(["2026-10-09", "2026-10-10"]);
    expect(line.series?.map((s) => s.name)).toEqual(["p50", "p95"]);
    expect(line.series?.[1].data).toEqual([480, null]); // 无 p95 样本日照常占位留白

    const bar = readOption("latency-tool-bar");
    expect(bar.xAxis?.data).toEqual(["get_quote"]);
    expect(bar.series?.map((s) => s.name)).toEqual(["p50", "p95"]);
    expect(fetchLatency).toHaveBeenCalledWith(7);
  });

  it("窗口切换：近 90 天重新取数", async () => {
    fetchLatency.mockResolvedValue(latencyAgg());

    render(<LatencyCharts />);
    fireEvent.click(await screen.findByRole("button", { name: "近 90 天" }));

    await waitFor(() => expect(fetchLatency).toHaveBeenLastCalledWith(90));
  });

  it("空态：无任何时延样本时渲染引导文案「对话产生后自动采集」", async () => {
    fetchLatency.mockResolvedValue(
      latencyAgg({
        turn: { p50Ms: null, p95Ms: null, byDay: [] },
        tool: { byTool: [] },
      }),
    );

    render(<LatencyCharts />);

    const empty = await screen.findByTestId("latency-empty");
    expect(empty.textContent).toContain("对话产生后自动采集");
    expect(screen.queryByTestId("latency-turn-line")).toBeNull();
  });
});
