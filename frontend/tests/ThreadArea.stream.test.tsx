import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Message } from "@ag-ui/client";
import ThreadArea from "@/components/chat/ThreadArea";
import { RuntimeProvider } from "@/components/chat/RuntimeProvider";
import { installConversationsApi } from "@/tests/mockConversationsApi";

// ———— 与 ThreadArea.test.tsx 相同的 mock 边界 ————
// 事件 → 消息状态的归约属于 @ag-ui/client（由 agui-stream.test.ts 用真实 HttpAgent 覆盖）；
// 本文件只验证我们自己的代码在流中断/错误场景下的行为：
// RUN_ERROR/网络失败 → sendError 横幅、isRunning 边沿 flush 部分消息、持久化失败不崩 UI。

const mocks = vi.hoisted(() => ({
  agent: {
    messages: [] as Message[],
    isRunning: false,
    addMessage: vi.fn(),
    setMessages: vi.fn(),
    abortRun: vi.fn(),
  },
  runAgent: vi.fn(),
  isReady: true,
  defaultToolRender: null as null | ((props: Record<string, unknown>) => React.ReactNode),
  renderToolCall: vi.fn(),
}));

vi.mock("@copilotkit/react-core/v2", () => ({
  useAgent: () => ({ agent: mocks.agent, isReady: mocks.isReady }),
  useCopilotKit: () => ({ copilotkit: { runAgent: mocks.runAgent } }),
  useDefaultRenderTool: ({ render }: { render: (p: Record<string, unknown>) => React.ReactNode }) => {
    mocks.defaultToolRender = render;
  },
  useRenderToolCall: () => mocks.renderToolCall,
  UseAgentUpdate: { OnMessagesChanged: "messages", OnRunStatusChanged: "run" },
}));

function agentMessage(m: Partial<Message> & { id: string }): Message {
  return m as Message;
}

function renderThread() {
  return render(
    <RuntimeProvider>
      <ThreadArea llmReady={null} />
    </RuntimeProvider>,
  );
}

const composerPlaceholder = "问行情、看走势、读财报… 例如：帮我看看贵州茅台最近的走势和估值";

let api: ReturnType<typeof installConversationsApi>;

beforeEach(() => {
  localStorage.clear();
  api = installConversationsApi();
  mocks.agent.messages = [];
  mocks.agent.isRunning = false;
  mocks.isReady = true;
  mocks.runAgent.mockReset();
  mocks.runAgent.mockResolvedValue(undefined);
  mocks.agent.addMessage.mockReset();
  mocks.agent.setMessages.mockReset();
  mocks.agent.abortRun.mockReset();
  mocks.renderToolCall.mockReset();
  mocks.renderToolCall.mockReturnValue(<div data-testid="tool-rendered" />);
  mocks.defaultToolRender = null;
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe("ThreadArea 流中断与错误场景", () => {
  it("runAgent 网络层失败（如 HTTP 502）：显示错误横幅，已收到部分消息仍渲染，可关闭", async () => {
    mocks.agent.messages = [
      agentMessage({ id: "u1", role: "user", content: "问题" }),
      agentMessage({ id: "a1", role: "assistant", content: "部分回答" }),
    ];
    mocks.runAgent.mockRejectedValue(new Error("HTTP 502"));
    renderThread();
    await waitFor(() => expect(screen.getByText("部分回答")).toBeTruthy());

    const ta = screen.getByPlaceholderText(composerPlaceholder);
    fireEvent.change(ta, { target: { value: "继续" } });
    fireEvent.keyDown(ta, { key: "Enter", shiftKey: false });
    expect(mocks.agent.addMessage).toHaveBeenCalled();
    await waitFor(() => expect(screen.getByText("HTTP 502")).toBeTruthy());
    // 错误横幅不影响已收到的部分消息
    expect(screen.getByText("部分回答")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "关闭错误提示" }));
    expect(screen.queryByText("HTTP 502")).toBeNull();
    // 让 400ms 防抖 flush 在卸载前走完（走 mock fetch），避免 cleanup 时 keepalive flush 撞到已恢复的真实 fetch
    await new Promise((r) => setTimeout(r, 500));
  });

  it("runAgent 非 Error 拒绝时回退默认文案", async () => {
    mocks.runAgent.mockRejectedValue("boom");
    renderThread();
    await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());
    fireEvent.click(screen.getByText(/贵州茅台/));
    await waitFor(() => expect(screen.getByText("请求失败，请稍后重试")).toBeTruthy());
  });

  it("发送失败后可重试（重发清空错误横幅）", async () => {
    mocks.runAgent.mockRejectedValueOnce(new Error("HTTP 502")).mockResolvedValue(undefined);
    renderThread();
    await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());

    fireEvent.click(screen.getByText(/贵州茅台/));
    await waitFor(() => expect(screen.getByText("HTTP 502")).toBeTruthy());

    fireEvent.click(screen.getByText(/大盘表现/));
    await waitFor(() => expect(mocks.runAgent).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByText("HTTP 502")).toBeNull());
  });

  it("RUN_ERROR 中断（isRunning true→false）：防抖窗口内的部分消息立即落库，不丢已收到内容", async () => {
    vi.useFakeTimers();
    api = installConversationsApi({ list: [{ id: "t1", title: "会话", updatedAt: 2 }] });
    mocks.agent.isRunning = true;
    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "问题" })];
    const view = renderThread();
    await act(async () => {});
    // 流式期间部分内容到来（截断快照：assistant 未收到完整回答）
    mocks.agent.messages = [
      agentMessage({ id: "u1", role: "user", content: "问题" }),
      agentMessage({ id: "a1", role: "assistant", content: "部分回答（流被截断）" }),
    ];
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await act(async () => {});
    // 400ms 防抖窗口内：尚未落库
    expect(api.state.messages.get("t1") ?? []).toHaveLength(0);
    // RUN_ERROR → isRunning 边沿翻转：立即 flush 已收到部分
    mocks.agent.isRunning = false;
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await act(async () => {});
    expect(api.state.messages.get("t1")).toEqual([
      expect.objectContaining({ id: "u1", role: "user", content: "问题" }),
      expect.objectContaining({ id: "a1", role: "assistant", content: "部分回答（流被截断）" }),
    ]);
  });

  it("持久化 PUT 失败：错误被吞掉记日志，消息仍渲染、UI 不崩", async () => {
    vi.useFakeTimers();
    const json = (body: unknown) =>
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/api/conversations" && method === "GET")
          return json([{ id: "t1", title: "会话", updatedAt: 2 }]);
        if (url === "/api/conversations/t1/messages" && method === "GET") return json([]);
        if (url === "/api/conversations/t1/messages" && method === "PUT")
          return new Response(JSON.stringify({ message: "写入失败" }), {
            status: 500,
            headers: { "Content-Type": "application/json" },
          });
        return json({ message: "not found" });
      }),
    );
    mocks.agent.isRunning = true;
    mocks.agent.messages = [
      agentMessage({ id: "u1", role: "user", content: "问题" }),
      agentMessage({ id: "a1", role: "assistant", content: "回答" }),
    ];
    const view = renderThread();
    await act(async () => {});
    // isRunning 边沿触发 flush → PUT 500 → 吞错
    mocks.agent.isRunning = false;
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await act(async () => {});
    // UI 仍渲染消息，无错误横幅（持久化失败不打断聊天）
    expect(screen.getByText("回答")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "关闭错误提示" })).toBeNull();
  });

  it("流式期间工具调用卡片：defaultToolRender 收到 inProgress 状态与半截参数 JSON 不崩", async () => {
    renderThread();
    await waitFor(() => expect(mocks.defaultToolRender).toBeTruthy());
    const node = mocks.defaultToolRender!({
      name: "get_quote",
      toolCallId: "c1",
      parameters: '{"code":', // 流式期间的半截 JSON
      status: "inProgress",
      result: undefined,
    });
    const { getByText } = render(<>{node}</>);
    expect(getByText("实时行情")).toBeTruthy();
    expect(getByText('{"code":')).toBeTruthy();
  });
});
