import { afterEach, describe, expect, it } from "vitest";
import { cleanup, render, screen } from "@testing-library/react";
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

describe("AnchorBadge（角标三态 + CSS group-hover 浮层）", () => {
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

  it("浮层常驻 DOM、group-hover 显隐（Sidebar 先例）", () => {
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
