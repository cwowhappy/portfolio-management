import { afterEach, describe, expect, it } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { AnchorBadge } from "@/components/shared/AnchorBadge";
import type { TrustAnchor } from "@/lib/trustMeta";

afterEach(() => cleanup());

// 服务层真实 anchor 形态（B5 TrustHookIntegrationTest 锁定的线上帧字段：tool/args/asOf/asOfKind/raw）
const VERIFIED: TrustAnchor = {
  snippet: "1520.33元",
  occ: 1,
  state: "verified",
  tool: "get_quote",
  args: { symbol: "600519.SH" },
  asOf: "2026-10-05 14:59:32",
  asOfKind: "data",
  raw: "1520.33",
};

const SOURCED: TrustAnchor = {
  snippet: "15.23元",
  occ: 1,
  state: "sourced",
  tool: "mcp_tushare_quote",
  args: { code: "600519.SH" },
  asOf: "2026-10-06 09:30:00",
  asOfKind: "call",
};

const UNVERIFIED: TrustAnchor = { snippet: "38倍", occ: 1, state: "unverified" };

describe("AnchorBadge（角标三态 + hover/click/focus 三通道浮层）", () => {
  it("verified：中性绿角标序号 + 正文数字原样渲染", () => {
    render(
      <AnchorBadge anchor={VERIFIED} label={1}>
        1520.33元
      </AnchorBadge>,
    );
    const badge = screen.getByTestId("trust-anchor-badge");
    expect(badge.textContent).toBe("1");
    expect(badge.getAttribute("data-anchor-state")).toBe("verified");
    expect(badge.className).toContain("text-[color:var(--color-down)]");
    expect(badge.parentElement?.textContent).toContain("1520.33元");
  });

  it("sourced：中性灰角标样式", () => {
    render(
      <AnchorBadge anchor={SOURCED} label={2}>
        15.23元
      </AnchorBadge>,
    );
    const badge = screen.getByTestId("trust-anchor-badge");
    expect(badge.getAttribute("data-anchor-state")).toBe("sourced");
    expect(badge.className).toContain("text-[color:var(--color-ink-dim)]");
  });

  it("unverified：弱灰角标样式（决策 #9：弱样式不满屏警示）", () => {
    render(
      <AnchorBadge anchor={UNVERIFIED} label={3}>
        38倍
      </AnchorBadge>,
    );
    const badge = screen.getByTestId("trust-anchor-badge");
    expect(badge.getAttribute("data-anchor-state")).toBe("unverified");
    expect(badge.className).toContain("text-[color:var(--color-ink-faint)]");
  });

  it("角标序号是可聚焦 button（type=button，键盘可达——终审裁定规格符合）", () => {
    render(
      <AnchorBadge anchor={VERIFIED} label={1}>
        1520.33元
      </AnchorBadge>,
    );
    const badge = screen.getByTestId("trust-anchor-badge");
    const button = screen.getByRole("button");
    expect(button.tagName).toBe("BUTTON");
    expect(button.getAttribute("type")).toBe("button");
    expect(badge.contains(button)).toBe(true);
    expect(button.textContent).toBe("1");
  });

  it("浮层常驻 DOM、group-hover 显隐保留（Sidebar 先例，触屏之外的指针通道）", () => {
    render(
      <AnchorBadge anchor={VERIFIED} label={1}>
        1520.33元
      </AnchorBadge>,
    );
    const popover = screen.getByRole("tooltip");
    expect(popover.className).toContain("opacity-0");
    expect(popover.className).toContain("group-hover:opacity-100");
    expect(popover.className).toContain("pointer-events-none");
    expect(popover.className).toContain("group-hover:pointer-events-auto");
  });

  // ———— 三通道开合（需求决策 #2「点击浮层」：click 触屏可达 + focus 键盘可达 + hover 保留） ————

  it("click 开合：点击角标浮层显（opacity-100 且不含 opacity-0），再点收回", () => {
    render(
      <AnchorBadge anchor={VERIFIED} label={1}>
        1520.33元
      </AnchorBadge>,
    );
    const button = screen.getByRole("button");
    const popover = screen.getByRole("tooltip");
    expect(button.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(button);
    expect(button.getAttribute("aria-expanded")).toBe("true");
    expect(popover.className).toContain("opacity-100");
    expect(popover.className).toContain("pointer-events-auto");
    expect(popover.className).not.toContain("opacity-0");
    fireEvent.click(button);
    expect(button.getAttribute("aria-expanded")).toBe("false");
    expect(popover.className).toContain("opacity-0");
  });

  it("Escape 关闭：click 打开后按 Escape 收回", () => {
    render(
      <AnchorBadge anchor={VERIFIED} label={1}>
        1520.33元
      </AnchorBadge>,
    );
    const button = screen.getByRole("button");
    fireEvent.click(button);
    fireEvent.keyDown(button, { key: "Escape" });
    expect(button.getAttribute("aria-expanded")).toBe("false");
    expect(screen.getByRole("tooltip").className).toContain("opacity-0");
  });

  it("focus 通道：聚焦即开、失焦即合（键盘 Tab 可达）", () => {
    render(
      <AnchorBadge anchor={VERIFIED} label={1}>
        1520.33元
      </AnchorBadge>,
    );
    const button = screen.getByRole("button");
    fireEvent.focus(button);
    expect(button.getAttribute("aria-expanded")).toBe("true");
    fireEvent.blur(button);
    expect(button.getAttribute("aria-expanded")).toBe("false");
  });

  it("指针点击路径：pointerdown 引发的 focus 不开浮层（click 切换不被紧跟的 focus 抵消）", () => {
    render(
      <AnchorBadge anchor={VERIFIED} label={1}>
        1520.33元
      </AnchorBadge>,
    );
    const button = screen.getByRole("button");
    fireEvent.pointerDown(button);
    fireEvent.focus(button);
    expect(button.getAttribute("aria-expanded")).toBe("false");
    fireEvent.click(button);
    expect(button.getAttribute("aria-expanded")).toBe("true");
  });

  it("aria 关联（补 F2-③ 欠账）：aria-describedby ↔ 浮层 id 一致", () => {
    render(
      <AnchorBadge anchor={VERIFIED} label={1}>
        1520.33元
      </AnchorBadge>,
    );
    const button = screen.getByRole("button");
    const popover = screen.getByRole("tooltip");
    const describedby = button.getAttribute("aria-describedby");
    expect(describedby).toBeTruthy();
    expect(popover.getAttribute("id")).toBe(describedby);
  });

  it("verified 浮层：数值一致措辞（决策 #13）+ 工具/参数/数据时间戳/raw 原值", () => {
    render(
      <AnchorBadge anchor={VERIFIED} label={1}>
        1520.33元
      </AnchorBadge>,
    );
    expect(screen.getByText("数值与工具返回一致")).toBeTruthy();
    expect(screen.queryByText(/已核实/)).toBeNull(); // 决策 #13：不出现「已核实」
    expect(screen.getByText("get_quote")).toBeTruthy();
    expect(screen.getByText("symbol：600519.SH")).toBeTruthy();
    expect(screen.getByText("数据时间戳：2026-10-05 14:59:32")).toBeTruthy();
    expect(screen.getByText("工具返回原值：1520.33")).toBeTruthy();
  });

  it("sourced 浮层：调用时刻标注、无 raw 行", () => {
    render(
      <AnchorBadge anchor={SOURCED} label={2}>
        15.23元
      </AnchorBadge>,
    );
    expect(screen.getByText(/已溯源未校验/)).toBeTruthy();
    expect(screen.getByText("mcp_tushare_quote")).toBeTruthy();
    expect(screen.getByText("code：600519.SH")).toBeTruthy();
    expect(screen.getByText("调用时刻：2026-10-06 09:30:00")).toBeTruthy();
    expect(screen.queryByText(/工具返回原值/)).toBeNull();
  });

  it("unverified 浮层：未溯源措辞、无工具/时间戳/raw 行", () => {
    render(
      <AnchorBadge anchor={UNVERIFIED} label={3}>
        38倍
      </AnchorBadge>,
    );
    expect(screen.getByText(/未溯源/)).toBeTruthy();
    expect(screen.queryByText(/工具：/)).toBeNull();
    expect(screen.queryByText(/时间戳|时刻/)).toBeNull();
    expect(screen.queryByText(/工具返回原值/)).toBeNull();
  });

  it("asOfKind 三态标注：data→数据时间戳 / generated→生成时刻 / call→调用时刻", () => {
    for (const [kind, label] of [
      ["data", "数据时间戳"],
      ["generated", "生成时刻"],
      ["call", "调用时刻"],
    ] as const) {
      const { unmount } = render(
        <AnchorBadge anchor={{ ...VERIFIED, asOfKind: kind }} label={1}>
          1520.33元
        </AnchorBadge>,
      );
      expect(screen.getByText(new RegExp(`^${label}：`))).toBeTruthy();
      unmount();
    }
  });
});
