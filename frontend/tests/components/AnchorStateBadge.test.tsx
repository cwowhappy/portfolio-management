import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import AnchorStateBadge from "@/components/research/AnchorStateBadge";
import type { ReportAnchorView } from "@/lib/reportSchemas";

// 假设锚点四态徽标 + 浮层（§3.8/§4.1）：色板沿 trust 角标体系（AnchorBadge 先例）
// ——FRESH 绿 / STALE 黄（--color-warn，本特性新增令牌）/ CHANGED 暖红 / UNVERIFIABLE 灰；
// hover/click/focus 三通道 + Escape + aria 关联全仿 AnchorBadge.tsx 形态。
// fixture 抄录 B13 锚点视图真实形态（currentValue 为字符串现值，不可得为 null）。

const FRESH: ReportAnchorView = {
  tool: "get_kline_daily",
  args: { symbol: "600519", period: "daily" },
  asOf: "2026-10-10T07:00:00Z",
  state: "FRESH",
  currentValue: "1580.00",
};

const STALE: ReportAnchorView = {
  tool: "get_quote",
  args: { symbol: "600519" },
  asOf: "2026-09-30T07:00:00Z",
  state: "STALE",
  currentValue: null,
};

const CHANGED: ReportAnchorView = {
  tool: "get_quote",
  args: { symbol: "600519" },
  asOf: "2026-10-10T07:00:00Z",
  state: "CHANGED",
  currentValue: "1520.33",
};

const UNVERIFIABLE: ReportAnchorView = {
  tool: "search_announcements",
  args: { keyword: "产能扩建" },
  asOf: null,
  state: "UNVERIFIABLE",
  currentValue: null,
};

afterEach(cleanup);

describe("AnchorStateBadge 四态渲染", () => {
  it("FRESH：绿（--color-down）「现值一致」+ data-anchor-state", () => {
    render(<AnchorStateBadge anchor={FRESH} seq={1} />);
    const badge = screen.getByTestId("report-anchor-badge");
    expect(badge.getAttribute("data-anchor-state")).toBe("FRESH");
    expect(badge.textContent).toBe("现值一致");
    expect(badge.className).toContain("text-[color:var(--color-down)]");
  });

  it("STALE：黄（--color-warn）「数据过期」", () => {
    render(<AnchorStateBadge anchor={STALE} seq={2} />);
    const badge = screen.getByTestId("report-anchor-badge");
    expect(badge.getAttribute("data-anchor-state")).toBe("STALE");
    expect(badge.textContent).toBe("数据过期");
    expect(badge.className).toContain("text-[color:var(--color-warn)]");
  });

  it("CHANGED：暖警示（--color-up）「现值有变」", () => {
    render(<AnchorStateBadge anchor={CHANGED} seq={3} />);
    const badge = screen.getByTestId("report-anchor-badge");
    expect(badge.getAttribute("data-anchor-state")).toBe("CHANGED");
    expect(badge.textContent).toBe("现值有变");
    expect(badge.className).toContain("text-[color:var(--color-up)]");
  });

  it("UNVERIFIABLE：弱灰「无法重验」", () => {
    render(<AnchorStateBadge anchor={UNVERIFIABLE} seq={4} />);
    const badge = screen.getByTestId("report-anchor-badge");
    expect(badge.getAttribute("data-anchor-state")).toBe("UNVERIFIABLE");
    expect(badge.textContent).toBe("无法重验");
    expect(badge.className).toContain("text-[color:var(--color-ink-faint)]");
  });

  it("aria-label 可达：含序号与四态语义", () => {
    render(<AnchorStateBadge anchor={FRESH} seq={1} />);
    expect(screen.getByTestId("report-anchor-badge").getAttribute("aria-label")).toBe(
      "假设 1 锚点状态：现值一致，点击查看重查详情",
    );
  });
});

describe("AnchorStateBadge 浮层（三通道开合）", () => {
  it("浮层常驻 DOM、group-hover 显隐保留（AnchorBadge 先例）", () => {
    render(<AnchorStateBadge anchor={FRESH} seq={1} />);
    const popover = screen.getByRole("tooltip");
    expect(popover.className).toContain("opacity-0");
    expect(popover.className).toContain("group-hover:opacity-100");
    expect(popover.className).toContain("pointer-events-none");
  });

  it("click 开合：aria-expanded 切换，再点收回", () => {
    render(<AnchorStateBadge anchor={FRESH} seq={1} />);
    const button = screen.getByRole("button");
    const popover = screen.getByRole("tooltip");
    expect(button.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(button);
    expect(button.getAttribute("aria-expanded")).toBe("true");
    expect(popover.className).toContain("opacity-100");
    expect(popover.className).not.toContain("opacity-0");
    fireEvent.click(button);
    expect(button.getAttribute("aria-expanded")).toBe("false");
    expect(popover.className).toContain("opacity-0");
  });

  it("Escape 关闭；focus 通道聚焦即开、失焦即合；pointerdown 引发的 focus 不开", () => {
    render(<AnchorStateBadge anchor={FRESH} seq={1} />);
    const button = screen.getByRole("button");
    fireEvent.click(button);
    fireEvent.keyDown(button, { key: "Escape" });
    expect(button.getAttribute("aria-expanded")).toBe("false");

    fireEvent.pointerDown(button);
    fireEvent.focus(button);
    expect(button.getAttribute("aria-expanded")).toBe("false"); // click 切换不被紧跟的 focus 抵消
    fireEvent.blur(button);
    fireEvent.focus(button);
    expect(button.getAttribute("aria-expanded")).toBe("true");
    fireEvent.blur(button);
    expect(button.getAttribute("aria-expanded")).toBe("false");
  });

  it("aria 关联：aria-describedby ↔ 浮层 id 一致", () => {
    render(<AnchorStateBadge anchor={FRESH} seq={1} />);
    const describedby = screen.getByRole("button").getAttribute("aria-describedby");
    expect(describedby).toBeTruthy();
    expect(screen.getByRole("tooltip").getAttribute("id")).toBe(describedby);
  });
});

describe("AnchorStateBadge 浮层内容（锚定真相 vs 现值对照）", () => {
  it("FRESH 浮层：一致措辞 + 工具/参数/数据时点/现值", () => {
    render(<AnchorStateBadge anchor={FRESH} seq={1} />);
    expect(screen.getByText("锚定数值现值再现（重查一致）")).toBeTruthy();
    expect(screen.getByText("get_kline_daily")).toBeTruthy();
    expect(screen.getByText("symbol：600519")).toBeTruthy();
    expect(screen.getByText("period：daily")).toBeTruthy();
    expect(screen.getByText("数据时点：2026-10-10T07:00:00Z")).toBeTruthy();
    expect(screen.getByText("现值（重查）：1580.00")).toBeTruthy();
  });

  it("CHANGED 浮层：漂移措辞 + 现值对照", () => {
    render(<AnchorStateBadge anchor={CHANGED} seq={3} />);
    expect(screen.getByText(/现值已漂移/)).toBeTruthy();
    expect(screen.getByText("现值（重查）：1520.33")).toBeTruthy();
  });

  it("STALE 浮层：超线措辞 + 现值不可得", () => {
    render(<AnchorStateBadge anchor={STALE} seq={2} />);
    expect(screen.getByText(/超出可信时效/)).toBeTruthy();
    expect(screen.getByText("现值（重查）：不可得")).toBeTruthy();
  });

  it("UNVERIFIABLE 浮层：不可重查措辞；asOf 为 null 不渲染数据时点行", () => {
    render(<AnchorStateBadge anchor={UNVERIFIABLE} seq={4} />);
    expect(screen.getByText(/不可重查/)).toBeTruthy();
    expect(screen.queryByText(/数据时点/)).toBeNull();
    expect(screen.getByText("现值（重查）：不可得")).toBeTruthy();
  });
});
