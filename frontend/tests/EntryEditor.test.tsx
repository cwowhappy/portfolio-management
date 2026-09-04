import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import EntryEditor from "@/components/journal/EntryEditor";
import * as journalApi from "@/lib/journalApi";

vi.mock("@/lib/journalApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/journalApi")>("@/lib/journalApi");
  return {
    ...actual,
    createEntry: vi.fn(),
    updateEntry: vi.fn(),
  };
});

const api = vi.mocked(journalApi);

async function fillBase(title = "买入茅台", content = "理由") {
  fireEvent.change(screen.getByPlaceholderText("标题"), { target: { value: title } });
  fireEvent.change(screen.getByPlaceholderText("内容（Markdown）"), { target: { value: content } });
}

async function clickSave() {
  fireEvent.click(screen.getByRole("button", { name: "保存记录" }));
}

beforeEach(() => {
  vi.resetAllMocks();
  api.createEntry.mockResolvedValue(undefined as never);
  api.updateEntry.mockResolvedValue(undefined as never);
});

afterEach(() => {
  cleanup();
});

describe("EntryEditor 表单校验", () => {
  it("BUY_MEMO 无 tradeId 时 stockCode 必填", async () => {
    render(<EntryEditor editing={null} onSaved={() => {}} onCancel={() => {}} />);
    await fillBase();
    await clickSave();
    expect(await screen.findByText(/股票代码/)).toBeTruthy();
    expect(api.createEntry).not.toHaveBeenCalled();
  });

  it("BUY_MEMO 提供 tradeId 时可省略 stockCode", async () => {
    render(<EntryEditor editing={null} onSaved={() => {}} onCancel={() => {}} />);
    await fillBase();
    fireEvent.change(screen.getByPlaceholderText("关联交易 ID（可选）"), { target: { value: "7" } });
    await clickSave();
    await vi.waitFor(() => expect(api.createEntry).toHaveBeenCalled());
    const input = api.createEntry.mock.calls[0][0] as { stockCode: string | null; tradeId: number | null };
    expect(input.stockCode).toBeNull();
    expect(input.tradeId).toBe(7);
  });

  it("RESEARCH_NOTE 无 stockCode 可创建（行业级笔记不绑定个股）", async () => {
    render(<EntryEditor editing={null} onSaved={() => {}} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "研究笔记" }));
    await fillBase();
    await clickSave();
    await vi.waitFor(() => expect(api.createEntry).toHaveBeenCalled());
    const input = api.createEntry.mock.calls[0][0] as { type: string; stockCode: string | null };
    expect(input.type).toBe("RESEARCH_NOTE");
    expect(input.stockCode).toBeNull();
  });

  it("BUY_MEMO 目标价/止损价需大于 0", async () => {
    render(<EntryEditor editing={null} onSaved={() => {}} onCancel={() => {}} />);
    await fillBase();
    fireEvent.change(screen.getByPlaceholderText("股票代码（如 600519）"), { target: { value: "600519" } });
    fireEvent.change(screen.getByPlaceholderText("目标价（可选）"), { target: { value: "-3" } });
    await clickSave();
    expect(await screen.findByText(/目标价需大于 0/)).toBeTruthy();
    expect(api.createEntry).not.toHaveBeenCalled();

    fireEvent.change(screen.getByPlaceholderText("目标价（可选）"), { target: { value: "1800" } });
    fireEvent.change(screen.getByPlaceholderText("止损价（可选）"), { target: { value: "-1" } });
    await clickSave();
    expect(await screen.findByText(/止损价需大于 0/)).toBeTruthy();
    expect(api.createEntry).not.toHaveBeenCalled();
  });

  it("REVIEW 复盘区间必填且 start≤end", async () => {
    render(<EntryEditor editing={null} onSaved={() => {}} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole("button", { name: "定期复盘" }));
    await fillBase("季度复盘", "复盘内容");
    await clickSave();
    expect(await screen.findByText(/请填写复盘区间/)).toBeTruthy();
    expect(api.createEntry).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("复盘开始"), { target: { value: "2026-12-31" } });
    fireEvent.change(screen.getByLabelText("复盘结束"), { target: { value: "2026-01-01" } });
    await clickSave();
    expect(await screen.findByText(/开始日期不能晚于结束日期/)).toBeTruthy();
    expect(api.createEntry).not.toHaveBeenCalled();

    fireEvent.change(screen.getByLabelText("复盘结束"), { target: { value: "2026-12-31" } });
    await clickSave();
    await vi.waitFor(() => expect(api.createEntry).toHaveBeenCalled());
  });
});
