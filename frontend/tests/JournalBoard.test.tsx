import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, it, expect, vi } from "vitest";
import JournalBoard from "@/components/journal/JournalBoard";
import * as journalApi from "@/lib/journalApi";
import type { JournalEntryView, TimelineEventView } from "@/lib/types";

vi.mock("@/lib/journalApi", async () => {
  const actual = await vi.importActual<typeof import("@/lib/journalApi")>("@/lib/journalApi");
  return {
    ...actual,
    fetchEntries: vi.fn(),
    fetchTimeline: vi.fn(),
    createEntry: vi.fn(),
    updateEntry: vi.fn(),
    deleteEntry: vi.fn(),
  };
});

const api = vi.mocked(journalApi);

const event = (title: string): TimelineEventView => ({
  type: "BUY", date: "2026-08-01", title, description: "desc", stockCode: "600519", stockName: "贵州茅台", refId: 1, refType: "TRADE",
});

beforeEach(() => {
  vi.resetAllMocks();
  api.fetchEntries.mockResolvedValue([] as JournalEntryView[]);
  api.fetchTimeline.mockResolvedValue([] as TimelineEventView[]);
});

afterEach(() => {
  cleanup();
});

describe("JournalBoard", () => {
  it("渲染标题与空时间线", async () => {
    render(<JournalBoard />);
    expect(await screen.findByText("投资决策记录")).toBeTruthy();
    expect(await screen.findByText(/暂无事件/)).toBeTruthy();
  });

  it("日期范围过滤：from/to 传给 fetchTimeline 并渲染结果", async () => {
    // 挂载 → 开始日期变化（中间）→ 结束日期变化（最终）
    api.fetchTimeline
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([])
      .mockResolvedValueOnce([event("过滤后事件")]);
    render(<JournalBoard />);
    await screen.findByText(/暂无事件/);
    expect(api.fetchTimeline).toHaveBeenLastCalledWith(undefined, undefined);

    fireEvent.change(screen.getByLabelText("开始日期"), { target: { value: "2026-01-01" } });
    fireEvent.change(screen.getByLabelText("结束日期"), { target: { value: "2026-12-31" } });
    expect(await screen.findByText("过滤后事件")).toBeTruthy();
    expect(api.fetchTimeline).toHaveBeenLastCalledWith("2026-01-01", "2026-12-31");
  });

  it("reload 竞态：丢弃过期响应，只保留最新一次加载结果", async () => {
    let resolveStale!: (v: TimelineEventView[]) => void;
    const staleGate = new Promise<TimelineEventView[]>((res) => { resolveStale = res; });
    api.fetchTimeline.mockResolvedValueOnce([]); // 挂载
    api.fetchTimeline.mockReturnValueOnce(staleGate); // reload #2（旧，挂起）
    api.fetchTimeline.mockResolvedValueOnce([event("FRESH")]); // reload #3（新）

    render(<JournalBoard />);
    await screen.findByText(/暂无事件/);
    expect(api.fetchTimeline).toHaveBeenCalledTimes(1);

    // 触发 reload #2（改变开始日期）
    fireEvent.change(screen.getByLabelText("开始日期"), { target: { value: "2026-01-01" } });
    await vi.waitFor(() => expect(api.fetchTimeline).toHaveBeenCalledTimes(2));

    // 触发 reload #3（再次改变开始日期）
    fireEvent.change(screen.getByLabelText("开始日期"), { target: { value: "2026-02-01" } });
    await vi.waitFor(() => expect(api.fetchTimeline).toHaveBeenCalledTimes(3));
    expect(await screen.findByText("FRESH")).toBeTruthy();

    // 放行过期 reload #2：守卫应丢弃，不被覆盖成 STALE
    await act(async () => { resolveStale([event("STALE")]); });
    await vi.waitFor(() => expect(api.fetchTimeline).toHaveBeenCalledTimes(3));
    expect(screen.queryByText("STALE")).toBeNull();
    expect(screen.getByText("FRESH")).toBeTruthy();
  });
});
