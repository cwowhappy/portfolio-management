import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import MemorySettingsPage from "@/components/memory/MemorySettingsPage";
import * as memoryApi from "@/lib/memoryApi";
import { MemoryApiError } from "@/lib/memoryApi";
import type { MemoryView } from "@/lib/memoryApi";

// 记忆管理区（MS-31 F2）：加载渲染 / 行删服务端回执 reload（非乐观）/ 行内编辑 /
// 节删 confirm / 409-410 冲突刷新 / 空态 / 文案红线两用例。memoryApi 逐函数打桩
// （importActual 保留 MemoryApiError 真类——组件 instanceof 分流依赖同一 class 身份）。

vi.mock("@/lib/memoryApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/memoryApi")>("@/lib/memoryApi");
  return {
    ...actual,
    fetchMemory: vi.fn(),
    deleteCuratedLine: vi.fn(),
    editCuratedLine: vi.fn(),
    deleteLedgerSection: vi.fn(),
  };
});

const api = vi.mocked(memoryApi);

const TITLE = "# MEMORY.md — Curated Long-Term Memory";

// B3 契约样例（ledgers 故意给正序——UI 必须按日倒序渲染）
const fullView = (): MemoryView => ({
  curated: {
    hash: "a1b2c3d4",
    lines: [TITLE, "## 偏好", "- 偏好白酒股"],
  },
  ledgers: [
    {
      date: "2026-10-09",
      hash: "f7g8h9i0",
      sections: [
        { header: "## Memory Flush — 2026-10-09T13:58:45Z", kind: "FLUSH", lines: ["- 复盘白酒板块"] },
      ],
    },
    {
      date: "2026-10-10",
      hash: "e5f6a7b8",
      sections: [
        { header: "## Memory Flush — 2026-10-10T13:58:45Z", kind: "FLUSH", lines: ["- 关注新能源"] },
        { header: "## 外部事件摘要 — 2026-10-10（a ~ b）", kind: "EXTERNAL_EVENT", lines: ["- 买入 600519"] },
      ],
    },
  ],
});

beforeEach(() => {
  vi.resetAllMocks();
  api.fetchMemory.mockResolvedValue(fullView());
  api.deleteCuratedLine.mockResolvedValue({ purgedLedgerLines: 0 });
  api.editCuratedLine.mockResolvedValue(undefined);
  api.deleteLedgerSection.mockResolvedValue(undefined);
});

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("MemorySettingsPage 记忆管理区", () => {
  it("初始加载：策展层行 + 日账本按日倒序渲染，kind 徽标中文标签", async () => {
    render(<MemorySettingsPage />);

    expect(await screen.findByText("- 偏好白酒股")).toBeTruthy();
    expect(screen.getByTestId("memory-settings")).toBeTruthy();
    expect(screen.getByTestId("memory-curated-list")).toBeTruthy();
    expect(screen.getByTestId("memory-ledger-list")).toBeTruthy();
    // kind 徽标（设计规格 §2.4：FLUSH→对话记忆 / EXTERNAL_EVENT→事件摘要；两 FLUSH 节）
    expect(screen.getAllByText("对话记忆").length).toBe(2);
    expect(screen.getByText("事件摘要")).toBeTruthy();
    // 按日倒序：fixture 正序给出，UI 须 2026-10-10 在 2026-10-09 之前
    const late = screen.getByText("2026-10-10");
    const early = screen.getByText("2026-10-09");
    expect(late.compareDocumentPosition(early) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("加载中：视图落地前显示加载态", () => {
    api.fetchMemory.mockImplementation(() => new Promise<MemoryView>(() => {}));
    render(<MemorySettingsPage />);
    expect(screen.getByText("加载中…")).toBeTruthy();
  });

  it("加载失败：顶部红字透出错误消息", async () => {
    api.fetchMemory.mockRejectedValue(new Error("网络异常"));
    render(<MemorySettingsPage />);
    expect(await screen.findByText("网络异常")).toBeTruthy();
  });

  it("行删除：DELETE 三字段寻址 + 服务端回执驱动 reload，回执视图删除生效", async () => {
    const updated = fullView();
    updated.curated.lines = [TITLE, "## 偏好"];
    api.fetchMemory.mockResolvedValueOnce(fullView()).mockResolvedValueOnce(updated);
    api.deleteCuratedLine.mockResolvedValueOnce({ purgedLedgerLines: 2 });
    render(<MemorySettingsPage />);
    await screen.findByText("- 偏好白酒股");

    fireEvent.click(screen.getByTestId("memory-curated-delete-2"));

    await waitFor(() =>
      expect(api.deleteCuratedLine).toHaveBeenCalledWith({
        lineIndex: 2,
        expectLine: "- 偏好白酒股",
        baseHash: "a1b2c3d4",
      }),
    );
    expect(api.fetchMemory).toHaveBeenCalledTimes(2); // 回执后再拉一次（非乐观）
    expect(await screen.findByText("已同步清除 2 行日记账，残余可在日账本手动清理")).toBeTruthy();
    await waitFor(() => expect(screen.queryByText("- 偏好白酒股")).toBeNull());
  });

  it("行删除非乐观：回执后 reload 视图未变则 UI 保留该行（无本地移除）", async () => {
    api.fetchMemory.mockResolvedValue(fullView()); // 两次都原样返回
    api.deleteCuratedLine.mockResolvedValue({ purgedLedgerLines: 0 });
    render(<MemorySettingsPage />);
    await screen.findByText("- 偏好白酒股");

    fireEvent.click(screen.getByTestId("memory-curated-delete-2"));

    await waitFor(() => expect(api.fetchMemory).toHaveBeenCalledTimes(2));
    expect(screen.getByText("- 偏好白酒股")).toBeTruthy(); // 行去留只由服务端视图决定
    expect(screen.queryByText(/已同步清除/)).toBeNull(); // purged=0 不提示
  });

  it("行编辑：行内 input 回填原文，保存 PUT 四字段 + reload 后新行渲染", async () => {
    const updated = fullView();
    updated.curated.lines = [TITLE, "## 偏好", "- 偏好新能源"];
    api.fetchMemory.mockResolvedValueOnce(fullView()).mockResolvedValueOnce(updated);
    render(<MemorySettingsPage />);
    await screen.findByText("- 偏好白酒股");

    fireEvent.click(screen.getByTestId("memory-curated-edit-2"));
    const input = screen.getByTestId("memory-curated-edit-input") as HTMLInputElement;
    expect(input.value).toBe("- 偏好白酒股");
    fireEvent.change(input, { target: { value: "- 偏好新能源" } });
    fireEvent.click(screen.getByTestId("memory-curated-edit-save"));

    await waitFor(() =>
      expect(api.editCuratedLine).toHaveBeenCalledWith({
        lineIndex: 2,
        expectLine: "- 偏好白酒股",
        newLine: "- 偏好新能源",
        baseHash: "a1b2c3d4",
      }),
    );
    expect(api.fetchMemory).toHaveBeenCalledTimes(2);
    expect(await screen.findByText("- 偏好新能源")).toBeTruthy();
    expect(screen.queryByTestId("memory-curated-edit-input")).toBeNull();
  });

  it("行编辑取消：退出行内编辑，不发起 PUT", async () => {
    render(<MemorySettingsPage />);
    await screen.findByText("- 偏好白酒股");

    fireEvent.click(screen.getByTestId("memory-curated-edit-2"));
    fireEvent.click(screen.getByTestId("memory-curated-edit-cancel"));

    expect(screen.queryByTestId("memory-curated-edit-input")).toBeNull();
    expect(api.editCuratedLine).not.toHaveBeenCalled();
    expect(screen.getByText("- 偏好白酒股")).toBeTruthy();
  });

  it("节删除：confirm 取消不调用；确认后 DELETE（date+节头+baseHash）+ reload 生效", async () => {
    const confirmSpy = vi.spyOn(window, "confirm").mockReturnValue(false);
    const updated = fullView();
    updated.ledgers[1].sections = [updated.ledgers[1].sections[1]]; // 2026-10-10 仅剩事件摘要节
    api.fetchMemory.mockResolvedValueOnce(fullView()).mockResolvedValueOnce(updated);
    render(<MemorySettingsPage />);
    await screen.findByText("- 关注新能源");

    fireEvent.click(screen.getByTestId("memory-section-delete-2026-10-10-0"));
    expect(api.deleteLedgerSection).not.toHaveBeenCalled();
    expect(screen.getByText("- 关注新能源")).toBeTruthy();

    confirmSpy.mockReturnValue(true);
    fireEvent.click(screen.getByTestId("memory-section-delete-2026-10-10-0"));
    await waitFor(() =>
      expect(api.deleteLedgerSection).toHaveBeenCalledWith("2026-10-10", {
        sectionHeader: "## Memory Flush — 2026-10-10T13:58:45Z",
        baseHash: "e5f6a7b8",
      }),
    );
    expect(api.fetchMemory).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(screen.queryByText("- 关注新能源")).toBeNull());
  });

  it("409 冲突：提示「内容已变化，已刷新」并 reload，红字不透出后端原文", async () => {
    api.deleteCuratedLine.mockRejectedValueOnce(
      new MemoryApiError("文件已被并发修改", 409, "MEMORY_CONFLICT"),
    );
    render(<MemorySettingsPage />);
    await screen.findByText("- 偏好白酒股");

    fireEvent.click(screen.getByTestId("memory-curated-delete-2"));

    expect(await screen.findByText("内容已变化，已刷新")).toBeTruthy();
    expect(api.fetchMemory).toHaveBeenCalledTimes(2);
    expect(screen.queryByText(/文件已被并发修改/)).toBeNull();
  });

  it("410 版本漂移：编辑保存冲突同样提示已刷新 + reload", async () => {
    api.editCuratedLine.mockRejectedValueOnce(new MemoryApiError("版本已过期", 410, "MEMORY_STALE"));
    render(<MemorySettingsPage />);
    await screen.findByText("- 偏好白酒股");

    fireEvent.click(screen.getByTestId("memory-curated-edit-2"));
    fireEvent.change(screen.getByTestId("memory-curated-edit-input"), {
      target: { value: "- 偏好新能源" },
    });
    fireEvent.click(screen.getByTestId("memory-curated-edit-save"));

    expect(await screen.findByText("内容已变化，已刷新")).toBeTruthy();
    expect(api.fetchMemory).toHaveBeenCalledTimes(2);
  });

  it("非冲突失败（400 只读）：顶部红字透出后端消息，不 reload", async () => {
    api.deleteCuratedLine.mockRejectedValueOnce(
      new MemoryApiError("标题行只读", 400, "MEMORY_READ_ONLY"),
    );
    render(<MemorySettingsPage />);
    await screen.findByText("- 偏好白酒股");

    fireEvent.click(screen.getByTestId("memory-curated-delete-2"));

    expect(await screen.findByText("标题行只读")).toBeTruthy();
    expect(api.fetchMemory).toHaveBeenCalledTimes(1);
  });

  it("空态：memory-empty-state + 「记忆如何产生」两条来源说明，列表不渲染", async () => {
    api.fetchMemory.mockResolvedValue({ curated: { hash: null, lines: [] }, ledgers: [] });
    render(<MemorySettingsPage />);

    expect(await screen.findByTestId("memory-empty-state")).toBeTruthy();
    expect(screen.getByText("记忆如何产生")).toBeTruthy();
    // 两条来源：对话自动抽取 flush + 事件摘要
    expect(screen.getByText(/对话记忆/)).toBeTruthy();
    expect(screen.getByText(/事件摘要/)).toBeTruthy();
    expect(screen.queryByTestId("memory-curated-list")).toBeNull();
    expect(screen.queryByTestId("memory-ledger-list")).toBeNull();
    // 文案红线在空态同样成立：不得出现彻底清除类承诺字样
    expect(document.body.textContent).not.toMatch(/彻底遗忘|彻底清除|完全删除/);
  });

  it("文案红线（正例）：purgedLedgerLines>0 提示句逐字匹配验收口径", async () => {
    const updated = fullView();
    updated.curated.lines = [TITLE, "## 偏好"];
    api.fetchMemory.mockResolvedValueOnce(fullView()).mockResolvedValueOnce(updated);
    api.deleteCuratedLine.mockResolvedValueOnce({ purgedLedgerLines: 3 });
    render(<MemorySettingsPage />);
    await screen.findByText("- 偏好白酒股");

    fireEvent.click(screen.getByTestId("memory-curated-delete-2"));

    const notice = await screen.findByTestId("memory-notice");
    expect(notice.textContent).toBe("已同步清除 3 行日记账，残余可在日账本手动清理");
  });

  it("文案红线（反例）：整页不出现「彻底遗忘/彻底清除/完全删除」承诺字样", async () => {
    const updated = fullView();
    updated.curated.lines = [TITLE, "## 偏好"];
    api.fetchMemory.mockResolvedValueOnce(fullView()).mockResolvedValueOnce(updated);
    api.deleteCuratedLine.mockResolvedValueOnce({ purgedLedgerLines: 1 });
    render(<MemorySettingsPage />);
    await screen.findByText("- 偏好白酒股");

    fireEvent.click(screen.getByTestId("memory-curated-delete-2"));
    await screen.findByText("已同步清除 1 行日记账，残余可在日账本手动清理");

    expect(document.body.textContent).not.toMatch(/彻底遗忘|彻底清除|完全删除/);
  });
});
