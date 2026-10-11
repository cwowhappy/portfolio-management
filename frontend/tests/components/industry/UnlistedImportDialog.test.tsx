import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { useState } from "react";
import UnlistedImportDialog from "@/components/industry/unlisted/UnlistedImportDialog";

vi.mock("@/lib/industryCurationApi", () => ({
  companiesTemplateHref: () => "/api/industry-curation/companies/import/template",
  fundingEventsTemplateHref: () => "/api/industry-curation/funding-events/import/template",
  importUnlistedCompanies: vi.fn(),
  importFundingEvents: vi.fn(),
}));

// 宿主壳：真实触发按钮 + 条件渲染对话框（对齐 UnlistedPanel 的挂载形态），供焦点往返断言
function Host({ onClose }: { onClose: () => void }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>
        批量导入
      </button>
      {open && (
        <UnlistedImportDialog
          onClose={() => {
            onClose();
            setOpen(false);
          }}
          onImported={() => {}}
        />
      )}
    </>
  );
}

afterEach(cleanup);

// P3-⑤：模态浮层可达性——Escape 关闭 + 打开时初始焦点入浮层 + 关闭后焦点返还触发元素 + Tab 圈定。
describe("UnlistedImportDialog 可达性（P3-⑤）", () => {
  it("打开聚焦首个控件；Escape 关闭（onClose 恰一次）并返还焦点给触发按钮", () => {
    const onClose = vi.fn();
    render(<Host onClose={onClose} />);
    const trigger = screen.getByRole("button", { name: "批量导入" });
    trigger.focus(); // 浏览器点击即聚焦，jsdom 需显式补齐
    fireEvent.click(trigger);

    const dialog = screen.getByTestId("curation-import-dialog");
    expect(dialog.getAttribute("role")).toBe("dialog");
    expect(dialog.getAttribute("aria-modal")).toBe("true");
    // 初始焦点入浮层：首个可聚焦元素（「策展企业」单选）
    expect(document.activeElement).toBe(screen.getAllByRole("radio")[0]);

    fireEvent.keyDown(dialog, { key: "Escape" });
    expect(screen.queryByTestId("curation-import-dialog")).toBeNull();
    expect(onClose).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(trigger);
  });

  it("Tab 在浮层内首尾循环：末位 Tab 回首位，首位 Shift+Tab 到末位", () => {
    const onClose = vi.fn();
    render(<Host onClose={onClose} />);
    const trigger = screen.getByRole("button", { name: "批量导入" });
    trigger.focus();
    fireEvent.click(trigger);
    const dialog = screen.getByTestId("curation-import-dialog");

    // 浮层内末位可聚焦元素：取消按钮
    const cancel = screen.getByRole("button", { name: "取消" });
    cancel.focus();
    fireEvent.keyDown(dialog, { key: "Tab" });
    expect(document.activeElement).toBe(screen.getAllByRole("radio")[0]);

    fireEvent.keyDown(dialog, { key: "Tab", shiftKey: true });
    expect(document.activeElement).toBe(cancel);
    expect(onClose).not.toHaveBeenCalled();
  });
});
