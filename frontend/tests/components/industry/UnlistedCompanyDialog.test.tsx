import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import UnlistedCompanyDialog from "@/components/industry/unlisted/UnlistedCompanyDialog";

vi.mock("@/lib/industryCurationApi", () => ({
  saveUnlistedCompany: vi.fn(),
}));

// 宿主壳：真实触发按钮 + 条件渲染对话框（对齐 UnlistedPanel 的挂载形态），供焦点往返断言
function Host({ onClose }: { onClose: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        新增企业
      </button>
      {open && (
        <UnlistedCompanyDialog
          industryCode="801120"
          initial={null}
          onClose={() => {
            onClose();
            setOpen(false);
          }}
          onChanged={() => {}}
        />
      )}
    </>
  );
}

afterEach(cleanup);

// P3-⑤（MS-31 C1 台账真清零）：模态浮层可达性——Escape 关闭 + 打开时初始焦点入浮层
// + 关闭后焦点返还触发元素 + Tab 圈定（useDialogA11y，沿 UnlistedImportDialog 接入口径）。
describe("UnlistedCompanyDialog 可达性（P3-⑤）", () => {
  it("打开聚焦首个控件；Escape 关闭（onClose 恰一次）并返还焦点给触发按钮", () => {
    const onClose = vi.fn();
    render(<Host onClose={onClose} />);
    const trigger = screen.getByRole("button", { name: "新增企业" });
    trigger.focus(); // 浏览器点击即聚焦，jsdom 需显式补齐
    fireEvent.click(trigger);

    const dialog = screen.getByTestId("unlisted-company-dialog");
    expect(dialog.getAttribute("role")).toBe("dialog");
    expect(dialog.getAttribute("aria-modal")).toBe("true");
    // 初始焦点入浮层：首个可聚焦元素（「企业名称」输入框）
    expect(document.activeElement).toBe(screen.getByLabelText(/企业名称（必填）/));

    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(screen.queryByTestId("unlisted-company-dialog")).toBeNull();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(trigger);
  });

  it("Tab 在浮层内首尾循环：末位 Tab 回首位，首位 Shift+Tab 到末位", () => {
    const onClose = vi.fn();
    render(<Host onClose={onClose} />);
    const trigger = screen.getByRole("button", { name: "新增企业" });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = screen.getByTestId("unlisted-company-dialog");

    // 浮层内末位可聚焦元素：取消按钮
    const cancel = screen.getByRole("button", { name: "取消" });
    cancel.focus();
    fireEvent.keyDown(dialog, { key: "Tab" });
    expect(document.activeElement).toBe(screen.getByLabelText(/企业名称（必填）/));

    fireEvent.keyDown(dialog, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(cancel);
    expect(onClose).not.toHaveBeenCalled();
  });
});
