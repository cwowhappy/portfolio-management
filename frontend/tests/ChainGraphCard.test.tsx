import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { ChainView } from "@/lib/types";

// echarts/core mock（照 LandscapeChart.test 先例）：fakeChart 补 on/off 捕获事件绑定
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

const { pushMock } = vi.hoisted(() => ({ pushMock: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ push: pushMock }) }));

import ChainGraphCard from "@/components/industry/chain/ChainGraphCard";

class ResizeObserverStub {
  observe = vi.fn();
  disconnect = vi.fn();
}

const chain: ChainView = {
  id: 7,
  name: "锂电池",
  description: "动力电池全产业链",
  stages: [
    { id: 11, tier: "UPSTREAM", tierLabel: "上游", name: "锂矿", sortOrder: 1, members: [
      { id: 21, memberType: "LISTED", stockCode: "300750", unlistedCompanyId: null, displayName: "宁德时代" },
    ] },
    { id: 12, tier: "DOWNSTREAM", tierLabel: "下游", name: "整车", sortOrder: 1, members: [
      { id: 22, memberType: "UNLISTED", stockCode: null, unlistedCompanyId: 9, displayName: "示例康源生物" },
    ] },
  ],
};

describe("ChainGraphCard", () => {
  beforeEach(() => {
    vi.stubGlobal("ResizeObserver", ResizeObserverStub);
  });
  afterEach(() => {
    cleanup();
    fakeChart.setOption.mockReset();
    fakeChart.on.mockReset();
    pushMock.mockReset();
    vi.unstubAllGlobals();
  });

  it("按链渲染卡片容器与链名/描述头，graph 节点带 tier 归属与 symbol 区分", async () => {
    render(<ChainGraphCard chain={chain} />);

    const card = await screen.findByTestId("chain-card-7");
    expect(card.textContent).toContain("锂电池");
    expect(card.textContent).toContain("动力电池全产业链");
    expect(screen.getByTestId("chain-graph-7")).toBeTruthy();
    await waitFor(() => expect(fakeChart.setOption).toHaveBeenCalled());
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const series = (fakeChart.setOption.mock.calls[0][0] as any).series as any[];
    expect(series[0].type).toBe("graph");
    expect(series[0].data).toHaveLength(2);
    // tier 归属：锂矿成员 category=0（上游）、整车成员 category=2（下游）；sub 附注含环节名
    expect(series[0].data[0].category).toBe(0);
    expect(series[0].data[0].sub).toBe("锂矿·上游");
    expect(series[0].data[0].symbol).toBe("circle");     // 上市
    expect(series[0].data[1].category).toBe(2);
    expect(series[0].data[1].symbol).toBe("diamond");    // 未上市
  });

  it("上市节点点击经 router 跳行情台；未上市节点（无 stockCode）不跳", async () => {
    render(<ChainGraphCard chain={chain} />);
    await waitFor(() => expect(fakeChart.on).toHaveBeenCalled());
    const [event, handler] = fakeChart.on.mock.calls[0] as [string, (p: unknown) => void];
    expect(event).toBe("click");

    handler({ data: { name: "宁德时代", stockCode: "300750" } });
    expect(pushMock).toHaveBeenCalledWith("/market?code=300750");
    pushMock.mockClear();
    handler({ data: { name: "示例康源生物" } });
    expect(pushMock).not.toHaveBeenCalled();
  });
});
