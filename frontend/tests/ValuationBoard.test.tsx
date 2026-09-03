import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { cleanup, render, screen, within } from "@testing-library/react";
import ValuationBoard from "@/components/valuation/ValuationBoard";
import * as valuationApi from "@/lib/valuationApi";

vi.mock("@/lib/valuationApi", () => ({
  fetchValuationOverview: vi.fn(),
  fetchValuationHistory: vi.fn(),
  fetchValuationIndustries: vi.fn(),
}));

const api = vi.mocked(valuationApi);

function mockOverview(dataAccumulating: boolean) {
  return {
    latestSnapshot: { tradingDay: "2026-08-27", peMedian: 19.14, pbMedian: 1.68, netBreakerCount: 220, netBreakerRatio: 0.041 },
    pePercentile: null, pbPercentile: null, netBreakerPercentile: null,
    erp: null, erpPercentile: null, thermometer: null, indices: [], dataAccumulating,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  api.fetchValuationOverview.mockResolvedValue(mockOverview(true));
  api.fetchValuationHistory.mockResolvedValue({ snapshots: [], treasuryYields: [], indexValuations: [] });
  api.fetchValuationIndustries.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
});

describe("ValuationBoard", () => {
  it("渲染标题与积累中标注", async () => {
    render(<ValuationBoard />);
    expect(await screen.findByText("市场估值仪表盘")).toBeTruthy();
    // 顶部角标 + 温度计空态 + 走势图空态均提示积累中
    expect((await screen.findAllByText(/数据积累中/)).length).toBeGreaterThan(0);
  });

  it("数据非积累中时不渲染顶部角标", async () => {
    api.fetchValuationOverview.mockResolvedValue(mockOverview(false));
    render(<ValuationBoard />);
    expect(await screen.findByText("市场估值仪表盘")).toBeTruthy();
    expect(screen.queryByText("数据积累中 · 分位仅供参考")).toBeNull();
  });

  it("渲染页脚免责声明", async () => {
    render(<ValuationBoard />);
    expect(await screen.findByText("市场估值仪表盘")).toBeTruthy();
    expect(screen.getByText(/不构成投资建议/)).toBeTruthy();
  });

  it("渲染破净股数量与占比（netBreakerCount + netBreakerRatio）", async () => {
    render(<ValuationBoard />);
    await screen.findByText("市场估值仪表盘");
    const grid = screen.getByTestId("stat-grid");
    expect(within(grid).getByText("破净股")).toBeTruthy();
    expect(within(grid).getByText(/220/)).toBeTruthy();
    expect(within(grid).getByText(/占比 4\.1%/)).toBeTruthy();
  });

  it("加载失败时显示错误文案", async () => {
    api.fetchValuationOverview.mockRejectedValue(new Error("估值服务不可用"));
    render(<ValuationBoard />);
    expect(await screen.findByText("加载失败：估值服务不可用")).toBeTruthy();
  });

  it("非 Error 异常回退为默认错误文案", async () => {
    api.fetchValuationOverview.mockRejectedValue("boom");
    render(<ValuationBoard />);
    expect(await screen.findByText("加载失败：加载失败")).toBeTruthy();
  });
});
