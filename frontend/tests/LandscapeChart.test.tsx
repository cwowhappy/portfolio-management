import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

// echarts/core mock（照 useECharts.test 先例）：fakeChart 补 on/off 捕获事件绑定
const { fakeChart, initSpy } = vi.hoisted(() => {
  const chart = {
    setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(),
    on: vi.fn(), off: vi.fn(),
  };
  return { fakeChart: chart, initSpy: vi.fn(() => chart) };
});
vi.mock("echarts/core", () => ({
  init: initSpy, registerTheme: vi.fn(), use: vi.fn(),
}));

const { stocksMock, companiesMock, pushMock } = vi.hoisted(() => ({
  stocksMock: vi.fn(), companiesMock: vi.fn(), pushMock: vi.fn(),
}));
vi.mock("@/lib/industryApi", () => ({ fetchIndustryStocks: stocksMock }));
vi.mock("@/lib/industryUnlistedApi", () => ({ fetchUnlistedCompanies: companiesMock }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock }) }));

import LandscapeChart from "@/components/industry/unlisted/LandscapeChart";

class ResizeObserverStub {
  observe = vi.fn();
  disconnect = vi.fn();
}

describe("LandscapeChart", () => {
  beforeEach(() => {
    stocksMock.mockResolvedValue([
      { stockCode: "002475", stockName: "立讯精密", totalMv: 2e12, revenue: null,
        revenueReportDate: null, roe: null, peTtm: null, pb: null, dividendYield: null, prosperity: null },
    ]);
    companiesMock.mockResolvedValue([
      { id: 1, industryCode: "801080", companyName: "示例华芯科技", segment: "半导体设备",
        latestRound: "B", latestRoundLabel: "B轮", lastFundingDate: "2026-06-15",
        totalFundingYi: 12.5, summary: null, sourceNote: null, updatedAt: "2026-09-26T00:00:00Z" },
    ]);
    vi.stubGlobal("ResizeObserver", ResizeObserverStub);
  });
  afterEach(() => {
    cleanup();
    fakeChart.setOption.mockReset();
    fakeChart.on.mockReset();
    stocksMock.mockReset();
    companiesMock.mockReset();
    pushMock.mockReset();
    vi.unstubAllGlobals();
  });

  it("组合 stocks+策展名单构建双色 scatter 并渲染 landscape-chart 容器", async () => {
    render(<LandscapeChart industryCode="801080" />);

    expect(await screen.findByTestId("landscape-chart")).toBeTruthy();
    await waitFor(() => expect(fakeChart.setOption).toHaveBeenCalled());
    const opt = fakeChart.setOption.mock.calls[0][0];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const series = opt.series as any[];
    expect(series.map((s) => s.type)).toEqual(["scatter", "scatter"]);
    // 市值换算：2e12 元 = 20000 亿；轮次序 B=5；策展点 y=5
    expect(series[0].data[0].value[1]).toBe(12);              // 上市 → IPO 带
    expect(series[0].data[0].marketCapYi).toBe(20000);
    expect(series[1].data[0].value[1]).toBe(5);               // 未上市策展 → B 轮带
    expect(stocksMock).toHaveBeenCalledWith("801080",
      expect.objectContaining({ sortBy: "total_mv", limit: 30 }));
  });

  it("上市气泡点击经 router 跳行情台（code 参数）", async () => {
    render(<LandscapeChart industryCode="801080" />);
    await waitFor(() => expect(fakeChart.on).toHaveBeenCalled());
    const [event, handler] = fakeChart.on.mock.calls[0] as [string, (p: unknown) => void];
    expect(event).toBe("click");

    handler({ data: { code: "002475", name: "立讯精密" } });
    expect(pushMock).toHaveBeenCalledWith("/market?code=002475");
    // 无 code（未上市气泡）不跳转
    pushMock.mockClear();
    handler({ data: { name: "示例华芯科技", round: "B轮" } });
    expect(pushMock).not.toHaveBeenCalled();
  });
});
