import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { cleanup, render } from "@testing-library/react";
import type { ReactNode } from "react";

// echarts/core mock init（useECharts 直接用）+ registerTheme（ensureAppThemes→registerAppThemes 默认参数会取它）
// vi.hoisted：vi.mock 工厂会被提升到静态 import 前，普通 const 撞 TDZ（Task 3 实测教训）
const { fakeChart, initSpy } = vi.hoisted(() => {
  const chart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() };
  return { fakeChart: chart, initSpy: vi.fn(() => chart) };
});
vi.mock("echarts/core", () => ({ init: initSpy, registerTheme: vi.fn() }));

import { useECharts } from "@/components/charts/useECharts";
import type { ECOption } from "@/lib/echarts-setup";

// jsdom 无 ResizeObserver，注入桩以捕获 resize 回调
let roCallback: (() => void) | null = null;
class ResizeObserverStub {
  observe = vi.fn();
  disconnect = vi.fn();
  constructor(cb: () => void) { roCallback = cb; }
}

function Probe({ option }: { option: ECOption; children?: ReactNode }) {
  const ref = useECharts(option);
  return <div ref={ref} data-testid="probe" />;
}

describe("useECharts", () => {
  beforeEach(() => {
    initSpy.mockClear(); fakeChart.setOption.mockClear(); fakeChart.dispose.mockClear();
    roCallback = null;
    vi.stubGlobal("ResizeObserver", ResizeObserverStub);
  });
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  it("挂载时 init 容器并 setOption(notMerge)", () => {
    const option = { series: [{ type: "pie" as const, data: [] }] };
    render(<Probe option={option} />);
    expect(initSpy).toHaveBeenCalledTimes(1);
    expect(fakeChart.setOption).toHaveBeenCalledWith(option, { notMerge: true });
  });

  it("容器尺寸变化时 resize", () => {
    render(<Probe option={{ series: [{ type: "bar" as const, data: [] }] }} />);
    roCallback?.();
    expect(fakeChart.resize).toHaveBeenCalledTimes(1);
  });

  it("option 引用变化时重新 setOption", () => {
    const { rerender } = render(<Probe option={{ series: [{ type: "line" as const, data: [] }] }} />);
    const next = { series: [{ type: "line" as const, data: [1, 2] }] };
    rerender(<Probe option={next} />);
    expect(fakeChart.setOption).toHaveBeenLastCalledWith(next, { notMerge: true });
  });

  it("卸载时 dispose 并断开监听（StrictMode 对称清理）", () => {
    const { unmount } = render(<Probe option={{ series: [{ type: "pie" as const, data: [] }] }} />);
    unmount();
    expect(fakeChart.dispose).toHaveBeenCalledTimes(1);
  });

  // 补充断言（brief 四用例之外）：卸载必须显式 disconnect ResizeObserver，与 dispose 对称
  it("卸载时断开 ResizeObserver 监听", () => {
    const created: Array<{ disconnect: ReturnType<typeof vi.fn> }> = [];
    class DisconnectTrackingStub {
      observe = vi.fn();
      disconnect = vi.fn();
      constructor(cb: () => void) { roCallback = cb; created.push(this); }
    }
    vi.stubGlobal("ResizeObserver", DisconnectTrackingStub);
    const { unmount } = render(<Probe option={{ series: [{ type: "bar" as const, data: [] }] }} />);
    expect(created).toHaveLength(1);
    unmount();
    expect(created[0]!.disconnect).toHaveBeenCalledTimes(1);
    expect(fakeChart.dispose).toHaveBeenCalledTimes(1);
  });
});
