import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import TraceTable from "@/components/admin/observability/TraceTable";
import { adminApi, type TraceItem, type TracePage } from "@/lib/adminApi";

vi.mock("@/lib/adminApi", () => ({
  adminApi: { fetchTrace: vi.fn() },
}));

const fetchTrace = vi.mocked(adminApi.fetchTrace);

// fixture 形态抄录自 Task 7 切片测试真实响应（ObservabilityControllerTest.traceRow）
const traceItem = (over: Partial<TraceItem> = {}): TraceItem => ({
  id: 3,
  userId: 2,
  conversationId: "conv-1",
  messageId: "msg-3",
  toolName: "get_quote",
  args: '{"code":"600519"}',
  resultText: "结果文本",
  specCount: 1,
  asOf: "2026-10-09",
  asOfKind: "TRADE_DATE",
  mcp: false,
  failed: true,
  durationMs: 120,
  calledAt: "2026-10-09T09:31:00Z",
  ...over,
});

const page = (items: TraceItem[], total = items.length, pageNumber = 0): TracePage => ({
  items,
  page: pageNumber,
  total,
});

beforeEach(() => {
  vi.clearAllMocks();
});

afterEach(() => {
  cleanup();
});

describe("观测区块①：工具调用明细表（TraceTable）", () => {
  it("挂载即取首页（page=0/size=20）并渲染明细行：上海时区时间/工具/参数预览/失败/时延", async () => {
    fetchTrace.mockResolvedValue(page([traceItem()]));

    render(<TraceTable />);

    expect(await screen.findByText("get_quote")).toBeTruthy();
    expect(screen.getByText("2026/10/09 17:31")).toBeTruthy(); // UTC 09:31 → 上海 17:31
    expect(screen.getByText('{"code":"600519"}')).toBeTruthy();
    expect(screen.getByText("失败")).toBeTruthy();
    expect(screen.getByText("120")).toBeTruthy();
    expect(screen.getByText("2026-10-09")).toBeTruthy(); // asOf 数据截止
    expect(fetchTrace).toHaveBeenCalledWith({ page: 0, size: 20 });
  });

  it("成功行渲染「成功」；args/resultText 为 null 显示 —；MCP 调用带标记", async () => {
    fetchTrace.mockResolvedValue(
      page([traceItem({ id: 8, failed: false, args: null, resultText: null, mcp: true, toolName: "mx_search" })]),
    );

    render(<TraceTable />);

    expect(await screen.findByText("成功")).toBeTruthy();
    expect(screen.getAllByText("—").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("MCP")).toBeTruthy();
  });

  it("筛选：工具（精确）+ 仅失败 + 起止日期（按 UTC 零点折算 ISO 时刻）经「查询」应用并回到第 0 页", async () => {
    fetchTrace.mockResolvedValue(page([traceItem()]));

    render(<TraceTable />);
    fireEvent.change(await screen.findByPlaceholderText("工具名（精确）"), {
      target: { value: "get_quote" },
    });
    fireEvent.change(screen.getByLabelText("仅失败"), { target: { value: "true" } });
    fireEvent.change(screen.getByLabelText("开始日期"), { target: { value: "2026-10-08" } });
    fireEvent.change(screen.getByLabelText("结束日期"), { target: { value: "2026-10-10" } });
    fireEvent.click(screen.getByRole("button", { name: "查询" }));

    await waitFor(() =>
      expect(fetchTrace).toHaveBeenLastCalledWith({
        page: 0,
        size: 20,
        tool: "get_quote",
        failed: true,
        from: "2026-10-08T00:00:00Z",
        to: "2026-10-10T00:00:00Z",
      }),
    );
  });

  it("分页：总页数指示 + 下一页/上一页翻页，首末页按钮禁用", async () => {
    fetchTrace.mockResolvedValueOnce(page([], 45, 0)); // 45 条 / 20 每页 → 3 页

    render(<TraceTable />);
    expect(await screen.findByText("第 1/3 页 · 共 45 条")).toBeTruthy();
    const prev = screen.getByRole("button", { name: "上一页" }) as HTMLButtonElement;
    expect(prev.disabled).toBe(true);

    fetchTrace.mockResolvedValueOnce(page([], 45, 1));
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(await screen.findByText("第 2/3 页 · 共 45 条")).toBeTruthy();
    expect(fetchTrace).toHaveBeenLastCalledWith({ page: 1, size: 20 });

    fetchTrace.mockResolvedValueOnce(page([], 45, 2));
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));
    expect(await screen.findByText("第 3/3 页 · 共 45 条")).toBeTruthy();
    expect((screen.getByRole("button", { name: "下一页" }) as HTMLButtonElement).disabled).toBe(true);

    fetchTrace.mockResolvedValueOnce(page([], 45, 1));
    fireEvent.click(screen.getByRole("button", { name: "上一页" }));
    await waitFor(() => expect(fetchTrace).toHaveBeenLastCalledWith({ page: 1, size: 20 }));
  });

  it("竞态守卫：翻页后过期的前一页响应被丢弃，仅渲染最新页数据", async () => {
    let resolvePage0!: (p: TracePage) => void;
    let resolvePage1!: (p: TracePage) => void;
    fetchTrace
      .mockImplementationOnce(() => new Promise<TracePage>((r) => (resolvePage0 = r)))
      .mockImplementationOnce(() => new Promise<TracePage>((r) => (resolvePage1 = r)));

    render(<TraceTable />);
    await screen.findByRole("button", { name: "下一页" }); // 首帧渲染后即在首屏翻页（两请求同时在途）
    fireEvent.click(screen.getByRole("button", { name: "下一页" }));

    await act(async () => {
      resolvePage1(page([traceItem({ id: 21, toolName: "tool-page-1" })], 40, 1));
    });
    await screen.findByText("tool-page-1");

    await act(async () => {
      resolvePage0(page([traceItem({ id: 1, toolName: "tool-page-0" })], 40, 0));
    });
    expect(screen.queryByText("tool-page-0")).toBeNull(); // 过期响应不回写
  });

  it("空态：无观测数据时渲染引导文案「对话产生后自动采集」", async () => {
    fetchTrace.mockResolvedValue(page([]));

    render(<TraceTable />);

    const empty = await screen.findByTestId("trace-empty");
    expect(empty.textContent).toContain("对话产生后自动采集");
  });

  it("加载失败显示错误信息", async () => {
    fetchTrace.mockRejectedValue(new Error("后端不可用"));

    render(<TraceTable />);

    expect(await screen.findByText("后端不可用")).toBeTruthy();
  });
});
