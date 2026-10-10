"use client";

import {
  useEffect,
  useRef,
  useState,
  type KeyboardEvent as ReactKeyboardEvent,
  type RefObject,
} from "react";

// P3-⑤（MS-31 F4）：轻量模态可达性钩子。不建共享 Dialog 组件（四处浮层形态差异大），
// 只把键盘/焦点行为收敛到一处：Escape 关闭（keydown 先例 AnchorBadge）+ 打开时初始焦点
// 入浮层（首个可聚焦元素，无则容器自身）+ 关闭后焦点返还触发元素 + Tab 圈定（浮层内首尾循环）。
// 各浮层自行决定 close 的门禁（如 saving/busy 在途不关），钩子不持有开合状态。

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * @param containerRef 浮层容器（overlay）ref；容器须设 tabIndex={-1} 以承接兜底焦点
 * @param close Escape 时的关闭回调（组件侧自行加 saving/busy 门禁）
 * @param active 浮层是否打开。两种挂载形态都支持：
 *   (a) 组件随浮层条件挂载（AdminBoard 弹窗 / UnlistedImportDialog）——active 恒真；
 *   (b) 常驻组件 + 条件浮层（ResearchNoteDialog 的 {open && <overlay/>}）——传 open。
 * @returns 挂到容器 onKeyDown 的处理器（Escape + Tab 圈定）
 */
export function useDialogA11y(
  containerRef: RefObject<HTMLElement | null>,
  close: () => void,
  active: boolean = true,
): (e: ReactKeyboardEvent) => void {
  // close 常为内联箭头函数（依赖 saving 等渲染态）：经 ref 转发最新闭包，处理器引用保持稳定
  const closeRef = useRef(close);
  useEffect(() => {
    closeRef.current = close;
  }, [close]);

  // 触发元素捕获（两时机互补，取有效者）：
  // - 首渲染捕获：浮层 DOM 尚未提交、autoFocus 未生效，activeElement 必是浮层外元素
  //   （形态 a 的正确值；形态 b 在组件挂载时捕获到的 body 不会被采用）；
  // - active 翻真时的 effect 捕获：若彼时 activeElement 仍在浮层外（形态 b 无 autoFocus 抢焦）
  //   即为触发元素；若已被浮层内 autoFocus 抢走则回退首渲染捕获（形态 a）。
  const [firstRenderTrigger] = useState<HTMLElement | null>(() =>
    typeof document !== "undefined" && document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null,
  );
  const triggerRef = useRef<HTMLElement | null>(null);
  const capturedRef = useRef(false);

  useEffect(() => {
    const el = containerRef.current;
    if (!el || !active) return;
    if (!capturedRef.current) {
      capturedRef.current = true;
      const a = document.activeElement;
      triggerRef.current =
        a instanceof HTMLElement && !el.contains(a) ? a : firstRenderTrigger;
    }
    // 初始焦点入浮层：首个可聚焦元素（浮层内 autoFocus 元素即首个，重复 focus 无冲突）
    const first = el.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
    (first ?? el).focus();
    return () => {
      // 关闭后焦点返还触发元素；触发元素已卸载（如列表重渲染）则让位浏览器默认
      const t = triggerRef.current;
      if (t && document.contains(t)) t.focus();
      capturedRef.current = false; // 下次打开重新捕获
    };
  }, [containerRef, active, firstRenderTrigger]);

  return (e: ReactKeyboardEvent) => {
    if (e.key === "Escape") {
      closeRef.current();
      return;
    }
    if (e.key !== "Tab") return;
    const el = containerRef.current;
    if (!el) return;
    const items = Array.from(el.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));
    if (items.length === 0) {
      e.preventDefault();
      el.focus();
      return;
    }
    const first = items[0];
    const last = items[items.length - 1];
    const current = document.activeElement;
    if (e.shiftKey) {
      // 首位 Shift+Tab（或焦点已逸出浮层）→ 末位
      if (current === first || !el.contains(current)) {
        e.preventDefault();
        last.focus();
      }
    } else if (current === last || !el.contains(current)) {
      // 末位 Tab（或焦点已逸出浮层）→ 首位
      e.preventDefault();
      first.focus();
    }
  };
}
