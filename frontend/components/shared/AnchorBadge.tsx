import { useId, useRef, useState, type ReactNode } from "react";
import type { TrustAnchor, TrustAnchorState, TrustAsOfKind } from "@/lib/trustMeta";

// MS-29 F2：数字级溯源角标 + 浮层（设计规格 §5.2，需求 F01/决策 #2「点击浮层」）。
// 三通道开合（终审裁定走实现路径·规格符合）：hover（group-hover CSS，先例 Sidebar）/
// click（触屏可达——受控 state 切换）/ focus（键盘 Tab 可达）；Escape 关闭；
// aria-expanded + aria-describedby↔浮层 id 关联（补 F2-③ 欠账）。
// 只读组件：消费 store/props 数据，不发任何请求。

/** 角标三态样式（决策 #5/#9/#13）：verified 中性绿 / sourced 中性灰 / unverified 弱灰。样式只落在角标序号上，不动正文数字（#9 弱样式不满屏警示）。 */
const BADGE_CLASS: Record<TrustAnchorState, string> = {
  verified: "text-[color:var(--color-down)]",
  sourced: "text-[color:var(--color-ink-dim)]",
  unverified: "text-[color:var(--color-ink-faint)]",
};

/** 浮层态措辞（决策 #13：verified 仅承诺数值一致性，不出现「已核实」）。 */
const STATE_TITLE: Record<TrustAnchorState, string> = {
  verified: "数值与工具返回一致",
  sourced: "已溯源未校验（来源工具返回，未做数值比对）",
  unverified: "未溯源：无工具数据支撑",
};

/** asOfKind 标注（决策 #17 语义：数据时点与调用时刻不得混同）。 */
const AS_OF_LABEL: Record<TrustAsOfKind, string> = {
  data: "数据时间戳",
  generated: "生成时刻",
  call: "调用时刻",
};

/**
 * 溯源角标：正文数字（children）原样 + 上标序号（label，anchors 数组序）+ 三通道浮层。
 * 浮层自建（仓库无 Popover 组件）：常驻 DOM、opacity 切换。focus 通道与 click 通道同为
 * 受控 state（pointerdown 置哨——指针点击引发的 focus 不开浮层，交由 click 切换，否则
 * click 的「合」会被紧跟的 focus「开」抵消）；hover 通道维持 group-hover CSS。
 */
export function AnchorBadge({
  anchor,
  label,
  children,
}: {
  anchor: TrustAnchor;
  label: number;
  children?: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const pointerDown = useRef(false);
  const popoverId = useId();
  const argsEntries = anchor.args ? Object.entries(anchor.args) : [];
  return (
    <span className="group relative" data-anchor-state={anchor.state}>
      {children}
      <sup
        data-testid="trust-anchor-badge"
        data-anchor-state={anchor.state}
        className={
          "ml-0.5 text-[10px] font-medium leading-none " + BADGE_CLASS[anchor.state]
        }
      >
        <button
          type="button"
          aria-expanded={open}
          aria-describedby={popoverId}
          aria-label={`溯源详情 ${label}（点击展开）`}
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
          className="cursor-help rounded-sm px-0.5 focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-[color:var(--color-accent)]"
        >
          {label}
        </button>
      </sup>
      <span
        id={popoverId}
        role="tooltip"
        data-testid="trust-anchor-popover"
        className={
          "absolute left-0 top-full z-30 mt-1 block w-64 max-w-[75vw] rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-panel-2)] px-3 py-2.5 text-left shadow-[var(--shadow-glow)] transition-opacity group-hover:pointer-events-auto group-hover:opacity-100 " +
          (open ? "pointer-events-auto opacity-100" : "pointer-events-none opacity-0")
        }
      >
        <span className="block text-[12px] font-medium text-[color:var(--color-ink)]">
          {STATE_TITLE[anchor.state]}
        </span>
        {anchor.tool && (
          <span className="mt-1.5 block text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
            工具：
            <code className="font-[family-name:var(--font-mono)] text-[color:var(--color-ink)]">
              {anchor.tool}
            </code>
          </span>
        )}
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
            {anchor.asOfKind ? AS_OF_LABEL[anchor.asOfKind] : "时间戳"}：{anchor.asOf}
          </span>
        )}
        {anchor.raw !== undefined && (
          <span className="mt-1 block text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
            工具返回原值：{anchor.raw}
          </span>
        )}
      </span>
    </span>
  );
}
