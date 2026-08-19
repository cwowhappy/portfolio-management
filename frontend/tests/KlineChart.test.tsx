import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import KlineChart from "@/components/market/KlineChart";
import type { KlineBar } from "@/lib/types";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

function bar(over: Partial<KlineBar> & { date: string }): KlineBar {
  return {
    open: 10,
    close: 11,
    high: 12,
    low: 9,
    volume: 1000,
    amount: 10000,
    amplitudePct: 1,
    ...over,
  };
}

const BARS = [1, 2, 3, 4, 5, 6, 7].map((i) =>
  bar({
    date: "2026-08-" + String(i).padStart(2, "0"),
    open: 10 + i,
    close: 10.5 + i,
    high: 11 + i,
    low: 9.5 + i,
    volume: 1000 * i,
  }),
);

function mockSvgRect(width = 760, height = 340, left = 0, top = 0) {
  return vi
    .spyOn(SVGElement.prototype, "getBoundingClientRect")
    .mockReturnValue({
      x: left,
      y: top,
      left,
      top,
      width,
      height,
      right: left + width,
      bottom: top + height,
      toJSON: () => ({}),
    } as DOMRect);
}

describe("KlineChart", () => {
  it("无数据时不渲染", () => {
    const { container } = render(<KlineChart bars={[]} />);
    expect(container.firstChild).toBeNull();
  });

  it("渲染 K线、成交量与两条均线", () => {
    const { container } = render(<KlineChart bars={BARS} />);
    expect(container.querySelectorAll("svg").length).toBe(1);
    expect(container.querySelectorAll("svg rect").length).toBeGreaterThanOrEqual(BARS.length * 2);
    const paths = container.querySelectorAll("path");
    expect(paths.length).toBe(2); // MA5 + MA20
    // 前 4 个点为 null，路径从第 5 根起（SVG 允许前导空格）
    expect(paths[0].getAttribute("d")).toContain("L");
    expect(container.querySelectorAll("line").length).toBeGreaterThan(0);
  });

  it("少于 5 根 K线时均线路径跳过空值（null 分支）", () => {
    const { container } = render(<KlineChart bars={BARS.slice(0, 3)} />);
    const ma5 = container.querySelectorAll("path")[0];
    // 前 4 个点都是 null，只有第 5 根起才有线段；3 根数据 → 只有 M 起点与孤立点
    expect(ma5.getAttribute("d")).not.toContain("L");
  });

  it("渲染坐标标签与最新价", () => {
    const { container } = render(<KlineChart bars={BARS} />);
    const texts = [...container.querySelectorAll("text")].map((t) => t.textContent);
    expect(texts).toContain(BARS[0].date);
    expect(texts).toContain(BARS[Math.floor(BARS.length / 2)].date);
    expect(texts).toContain(BARS[BARS.length - 1].date);
    expect(texts).toContain(BARS[BARS.length - 1].close.toFixed(2));
  });

  it("鼠标悬停显示十字详情，移出后消失", () => {
    const rectSpy = mockSvgRect();
    const { container } = render(<KlineChart bars={BARS} />);
    const svg = container.querySelector("svg")!;
    // 最后一个 K线：step = (760-10-64)/7 ≈ 98，第 7 根中心 ≈ 10 + 98*6 + 49 = 647
    fireEvent.mouseMove(svg, { clientX: 647, clientY: 100 });
    // 详情面板出现（日期在 x 轴标签里也存在，因此断言详情特有文案）
    expect(screen.getByText(/开 /)).toBeTruthy();
    expect(screen.getByText(/手/)).toBeTruthy();
    fireEvent.mouseLeave(svg);
    expect(screen.queryByText(/开 /)).toBeNull();
    rectSpy.mockRestore();
  });

  it("悬停在图表左边缘之外时清空详情", () => {
    const rectSpy = mockSvgRect();
    const { container } = render(<KlineChart bars={BARS} />);
    const svg = container.querySelector("svg")!;
    fireEvent.mouseMove(svg, { clientX: 0, clientY: 0 }); // x - PAD.l < 0 → null
    expect(screen.queryByText(/开 /)).toBeNull();
    rectSpy.mockRestore();
  });

  it("包含下跌 K线（close < open）时使用跌色", () => {
    const bars = [
      bar({ date: "2026-08-01", open: 12, close: 11, high: 12.5, low: 10.5 }),
      ...BARS.slice(0, 4),
    ];
    const { container } = render(<KlineChart bars={bars} />);
    const g = container.querySelectorAll("svg g");
    // 第一根是跌：内部 rect 使用背景填充
    const first = g[0];
    expect(first.querySelector("rect")?.getAttribute("fill")).toBe("var(--color-bg)");
  });
});
