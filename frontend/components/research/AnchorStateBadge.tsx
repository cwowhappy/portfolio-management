"use client";

import { useId, useRef, useState } from "react";
import type { AnchorState, ReportAnchorView } from "@/lib/reportSchemas";

// MS-32 F2：假设锚点四态徽标 + 浮层（设计规格 §3.8/§4.1）。色板沿 trust 角标体系
// （AnchorBadge 先例）+ 新增 STALE 黄（--color-warn 令牌）与 CHANGED 暖警示：
// FRESH 现值再现（绿）/ STALE 数据时点超线（黄）/ CHANGED 现值漂移（暖红）/
// UNVERIFIABLE 不可重查（灰）。state/currentValue 为打开详情时重查的瞬态（不落库）。
// 三通道开合（hover 的 group-hover CSS / click 触屏可达 / focus 键盘可达）+ Escape
// 关闭 + aria-expanded/aria-describedby 全仿 AnchorBadge.tsx 形态。只读组件：不发请求。

/** 四态徽标样式（chip 形态，色只落在徽标上不动假设正文——沿决策 #9 弱样式纪律）。 */
const BADGE_CLASS: Record<AnchorState, string> = {
  FRESH: "border-[color:var(--color-down)]/50 text-[color:var(--color-down)]",
  STALE: "border-[color:var(--color-warn)]/50 text-[color:var(--color-warn)]",
  CHANGED: "border-[color:var(--color-up)]/50 text-[color:var(--color-up)]",
  UNVERIFIABLE: "border-[color:var(--color-line)] text-[color:var(--color-ink-faint)]",
};

/** 四态徽标文案。 */
const STATE_LABEL: Record<AnchorState, string> = {
  FRESH: "现值一致",
  STALE: "数据过期",
  CHANGED: "现值有变",
  UNVERIFIABLE: "无法重验",
};

/** 浮层态措辞（§3.8 判定语义：容差内再现 FRESH，否则 CHANGED；超 stale 线 STALE；重查失败 UNVERIFIABLE）。 */
const STATE_TITLE: Record<AnchorState, string> = {
  FRESH: "锚定数值现值再现（重查一致）",
  STALE: "锚定数据时点已超出可信时效",
  CHANGED: "锚定数值现值已漂移（与研报撰写时不一致）",
  UNVERIFIABLE: "锚点不可重查（工具不可用或不在重查白名单）",
};

/**
 * 假设锚点徽标：四态 chip + 浮层（工具/参数/数据时点锚定真相 vs 现值重查对照）。
 * 浮层常驻 DOM、opacity 切换；pointerdown 置哨防 click「合」被紧跟的 focus「开」抵消。
 */
export default function AnchorStateBadge({ anchor, seq }: {
  anchor: ReportAnchorView;
  /** 假设序号（aria-label 与浮层定位用）。 */
  seq: number;
}) {
  const [open, setOpen] = useState(false);
  const pointerDown = useRef(false);
  const popoverId = useId();
  const argsEntries = anchor.args ? Object.entries(anchor.args) : [];
  return (
    <span className="group relative inline-flex shrink-0" data-anchor-state={anchor.state}>
      <button
        type="button"
        data-testid="report-anchor-badge"
        data-anchor-state={anchor.state}
        aria-expanded={open}
        aria-describedby={popoverId}
        aria-label={`假设 ${seq} 锚点状态：${STATE_LABEL[anchor.state]}，点击查看重查详情`}
        onPointerDown={() => {
          pointerDown.current = true;
        }}
        onClick={() => setOpen((v) => !v)}
        onFocus={() => {
          if (!pointerDown.current) setOpen(true);
        }}
        onBlur={() => {
          pointerDown.current = false;
          setOpen(false);
        }}
        onKeyDown={(e) => {
          if (e.key === "Escape") setOpen(false);
        }}
        className={`cursor-help rounded border px-1.5 py-0.5 text-[11px] leading-tight focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[color:var(--color-accent)] ${BADGE_CLASS[anchor.state]}`}
      >
        {STATE_LABEL[anchor.state]}
      </button>
      <span
        id={popoverId}
        role="tooltip"
        data-testid="report-anchor-popover"
        className={
          "absolute left-0 top-full z-30 mt-1 block w-64 max-w-[75vw] rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-panel-2)] px-3 py-2.5 text-left shadow-[var(--shadow-glow)] transition-opacity group-hover:pointer-events-auto group-hover:opacity-100 " +
          (open ? "pointer-events-auto opacity-100" : "pointer-events-none opacity-0")
        }
      >
        <span className="block text-[12px] font-medium text-[color:var(--color-ink)]">
          {STATE_TITLE[anchor.state]}
        </span>
        <span className="mt-1.5 block text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
          工具：
          <code className="font-[family-name:var(--font-mono)] text-[color:var(--color-ink)]">
            {anchor.tool}
          </code>
        </span>
        {argsEntries.length > 0 && (
          <span className="mt-1 block text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
            {argsEntries.map(([key, val]) => (
              <span key={key} className="block break-all">
                {key}：{String(val)}
              </span>
            ))}
          </span>
        )}
        {anchor.asOf && (
          <span className="mt-1 block text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
            数据时点：{anchor.asOf}
          </span>
        )}
        <span className="mt-1 block text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
          现值（重查）：{anchor.currentValue ?? "不可得"}
        </span>
      </span>
    </span>
  );
}
