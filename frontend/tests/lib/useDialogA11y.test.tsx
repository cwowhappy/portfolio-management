import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useRef, useState } from "react";
import { useDialogA11y } from "@/lib/useDialogA11y";

afterEach(cleanup);

/** 无可聚焦元素的浮层：初始焦点兜底到容器自身，Tab 原地圈定不逸出 */
function BareOverlay({ close }: { close: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const onKeyDown = useDialogA11y(ref, close);
  return (
    <div ref={ref} data-testid="bare-overlay" tabIndex={-1} onKeyDown={onKeyDown}>
      <p>纯文本内容</p>
    </div>
  );
}

/** 含两个按钮的浮层 + 浮层外一个「旁路」可聚焦元素（模拟焦点逸出浮层的场景） */
function OverlayWithEscapee({ close }: { close: () => void }) {
  const ref = useRef<HTMLDivElement>(null);
  const onKeyDown = useDialogA11y(ref, close);
  return (
    <div>
      <button type="button" data-testid="outside">
        浮层外
      </button>
      <div ref={ref} data-testid="overlay" tabIndex={-1} onKeyDown={onKeyDown}>
        <button type="button" data-testid="first">
          首位
        </button>
        <button type="button" data-testid="last">
          末位
        </button>
      </div>
    </div>
  );
}

/** 常驻组件 + active 门禁形态：active=false 时钩子不接管焦点（ResearchNoteDialog 接线形态） */
function GatedOverlay({ close }: { close: () => void }) {
  const [active, setActive] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const onKeyDown = useDialogA11y(ref, close, active);
  return (
    <div>
      <button type="button" data-testid="toggle" onClick={() => setActive((v) => !v)}>
        开关
      </button>
      {active && (
        <div ref={ref} data-testid="gated-overlay" tabIndex={-1} onKeyDown={onKeyDown}>
          <button type="button" data-testid="in-first">
            首位
          </button>
        </div>
      )}
    </div>
  );
}

describe("useDialogA11y（P3-⑤ 钩子单元）", () => {
  it("浮层内无可聚焦元素：初始焦点落到容器自身；Tab 原地圈定且不逸出", () => {
    const close = vi.fn();
    render(<BareOverlay close={close} />);
    const overlay = screen.getByTestId("bare-overlay");
    expect(document.activeElement).toBe(overlay);
    // Tab：无圈定对象 → 阻止默认并聚焦容器（不出浮层）
    fireEvent.keyDown(overlay, { key: "Tab" });
    expect(document.activeElement).toBe(overlay);
    fireEvent.keyDown(overlay, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(overlay);
    expect(close).not.toHaveBeenCalled();
  });

  it("焦点逸出浮层后按 Tab：前向回到首位、反向回末位（圈定兜底分支）", () => {
    const close = vi.fn();
    render(<OverlayWithEscapee close={close} />);
    const overlay = screen.getByTestId("overlay");
    // 焦点在浮层外元素上（如用户点到了浮层外）
    screen.getByTestId("outside").focus();
    fireEvent.keyDown(overlay, { key: "Tab" });
    expect(document.activeElement).toBe(screen.getByTestId("first"));
    fireEvent.keyDown(overlay, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(screen.getByTestId("last"));
  });

  it("active=false 期间不接管焦点；翻真时捕获触发元素并在翻假后返还", () => {
    const close = vi.fn();
    render(<GatedOverlay close={close} />);
    const toggle = screen.getByTestId("toggle");
    expect(screen.queryByTestId("gated-overlay")).toBeNull();
    toggle.focus();
    fireEvent.click(toggle); // active=true
    expect(document.activeElement).toBe(screen.getByTestId("in-first"));
    fireEvent.click(toggle); // active=false → 卸载浮层，焦点返还触发元素
    expect(screen.queryByTestId("gated-overlay")).toBeNull();
    expect(document.activeElement).toBe(toggle);
  });
});
