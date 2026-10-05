import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Message } from "@ag-ui/client";
import ThreadArea from "@/components/chat/ThreadArea";
import { RuntimeProvider, useChatRuntime } from "@/components/chat/RuntimeProvider";
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
    subscribe: vi.fn(),
  },
  runAgent: vi.fn(),
  isReady: true,
  defaultToolRender: null as null | ((props: Record<string, unknown>) => React.ReactNode),
  renderToolCall: vi.fn(),
  interruptProps: null as { interrupts: unknown[]; resolve: (p: unknown, id?: string) => void } | null,
}));

vi.mock("@copilotkit/react-core/v2", () => ({
  // RuntimeProvider 引入的 chat 配置 provider（threadId 绑定，ADR-0012）：透传渲染即可
  CopilotChatConfigurationProvider: ({ children }: { children: React.ReactNode }) => children,
  useAgent: () => ({ agent: mocks.agent, isReady: mocks.isReady }),
  useCopilotKit: () => ({ copilotkit: { runAgent: mocks.runAgent } }),
  useDefaultRenderTool: ({ render }: { render: (p: Record<string, unknown>) => React.ReactNode }) => {
    mocks.defaultToolRender = render;
  },
  useInterrupt: (config: { render: (p: unknown) => React.ReactNode }) =>
    mocks.interruptProps ? config.render(mocks.interruptProps) : null,
  useRenderToolCall: () => mocks.renderToolCall,
  // ChartToolRenderers（图表具名渲染器）挂进 ToolCallRenderers 后，模块 mock 需补该导出（无操作即可）
  useRenderTool: () => {},
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

/** 切线程夹具（与 ThreadArea.test.tsx 相同）：驱动 RuntimeProvider.switchThread 真实切线程。 */
function ThreadSwitchHarness({ targetId }: { targetId: string }) {
  const { switchThread } = useChatRuntime();
  return (
    <>
      <button onClick={() => switchThread(targetId)}>切到 {targetId}</button>
      <ThreadArea llmReady={null} />
    </>
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
  mocks.agent.subscribe.mockReset();
  mocks.agent.subscribe.mockReturnValue({ unsubscribe: vi.fn() });
  mocks.renderToolCall.mockReset();
  mocks.renderToolCall.mockReturnValue(<div data-testid="tool-rendered" />);
  mocks.defaultToolRender = null;
  mocks.interruptProps = null;
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
    // issue #27 闸门：发送需等当前线程回灌完成（setMessages 被调即回灌落地）
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());

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
    // issue #27 闸门：发送需等当前线程回灌完成（setMessages 被调即回灌落地）
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    fireEvent.click(screen.getByText(/贵州茅台/));
    await waitFor(() => expect(screen.getByText("请求失败，请稍后重试")).toBeTruthy());
  });

  it("发送失败后可重试（重发清空错误横幅）", async () => {
    mocks.runAgent.mockRejectedValueOnce(new Error("HTTP 502")).mockResolvedValue(undefined);
    renderThread();
    await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());
    // issue #27 闸门：发送需等当前线程回灌完成（setMessages 被调即回灌落地）
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());

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
        if (url === "/api/conversations/t1/messages" && method === "GET")
          return json({ updatedAt: "2026-10-04T00:00:00.000Z", messages: [] });
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

// ———— P2-F3：flushPersist 并发序号守卫 ————
// 运行停止 flush 与防抖/keepalive flush 重叠时，各自 async IIFE 并发：先发起者的 PUT 可能
// 后完成，用旧快照覆盖新快照。守卫语义：后发起的 flush 代表更新的消息快照；旧的让其自然作废
// ——但序号必须按线程隔离：跨线程 PUT 按 URL 隔离本就安全，全局序号会误杀旧线程在途 flush。
// 各用例都用闸门式 fetch mock 精确控制 resolve 顺序，以 mock 收集的 PUT 调用序列为准断言。
// 注意：mock 刻意不做 If-Match 校验——隔离验证序号守卫本身（若同时启用乐观锁，
// 409+union 兜底会掩盖过期写，断言焦点就不再是守卫行为）。

describe("ThreadArea flushPersist 并发序号守卫（P2-F3）", () => {
  const json = (status: number, body: unknown) =>
    new Response(JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });

  it("两次 flush 重叠：后发起的新快照先落库，先发起者的过期写被丢弃", async () => {
    let getMsgCount = 0;
    let releaseFlush1Load!: (r: Response) => void;
    const flush1LoadGate = new Promise<Response>((res) => {
      releaseFlush1Load = res;
    });
    const puts: Array<{ status: number; ids: string[] }> = [];
    let serverIds: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/api/conversations" && method === "GET")
          return json(200, [{ id: "t1", title: "会话", updatedAt: 2 }]);
        if (url === "/api/conversations/t1/messages" && method === "GET") {
          getMsgCount += 1;
          if (getMsgCount === 1) return json(200, { updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }); // 历史回灌
          if (getMsgCount === 2) return flush1LoadGate; // flush #1 的 loadMessages：挂起等放行
          return json(200, { updatedAt: "2026-10-04T00:00:02.000Z", messages: [] });
        }
        if (url === "/api/conversations/t1/messages" && method === "PUT") {
          const ids = (JSON.parse(String(init?.body)) as Array<{ id: string }>).map((m) => m.id);
          puts.push({ status: 200, ids });
          serverIds = ids; // mock 不校验 If-Match：后完成的 PUT 直接整体覆盖内存态
          return json(200, { updatedAt: "2026-10-04T00:00:03.000Z" });
        }
        return json(404, { message: "not found" });
      }),
    );

    mocks.agent.isRunning = true;
    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "问题" })];
    const view = renderThread();
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    // 运行停止（isRunning true→false）：立即 flush 旧快照 [u1]（flush #1），其 loadMessages 挂起
    mocks.agent.isRunning = false;
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(getMsgCount).toBe(2));
    // 消息更新（新快照 [u1, a1]）→ 400ms 防抖发起 flush #2，先完成落库
    mocks.agent.messages = [
      agentMessage({ id: "u1", role: "user", content: "问题" }),
      agentMessage({ id: "a1", role: "assistant", content: "回答" }),
    ];
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(puts).toHaveLength(1));
    expect(puts[0]).toEqual({ status: 200, ids: ["u1", "a1"] });
    // 放行 flush #1 的 loadMessages（拿到的是早于 flush #2 落库的旧 view）：
    // 它已过期，不得再发起 PUT——否则 [u1] 旧快照整体覆盖 [u1, a1]
    releaseFlush1Load(json(200, { updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }));
    await new Promise((r) => setTimeout(r, 300));
    expect(puts).toHaveLength(1);
    expect(serverIds).toEqual(["u1", "a1"]);
  });

  it("409 重试路径同样受守卫：重读期间新 flush 已发起 → 不再发起重试 PUT", async () => {
    let getMsgCount = 0;
    let releaseFlush1Reload!: (r: Response) => void;
    const flush1ReloadGate = new Promise<Response>((res) => {
      releaseFlush1Reload = res;
    });
    const puts: Array<{ status: number; ids: string[] }> = [];
    let serverIds: string[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/api/conversations" && method === "GET")
          return json(200, [{ id: "t1", title: "会话", updatedAt: 2 }]);
        if (url === "/api/conversations/t1/messages" && method === "GET") {
          getMsgCount += 1;
          if (getMsgCount === 1) return json(200, { updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }); // 历史回灌
          if (getMsgCount === 2) return json(200, { updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }); // flush #1 首次 load
          if (getMsgCount === 3) return flush1ReloadGate; // flush #1 撞 409 后的重读：挂起等放行
          return json(200, { updatedAt: "2026-10-04T00:00:02.000Z", messages: [] }); // flush #2 的 load
        }
        if (url === "/api/conversations/t1/messages" && method === "PUT") {
          const ids = (JSON.parse(String(init?.body)) as Array<{ id: string }>).map((m) => m.id);
          const status = puts.length === 0 ? 409 : 200; // 首次 PUT 撞 409（并发冲突），此后放行
          puts.push({ status, ids });
          if (status === 200) serverIds = ids;
          return status === 409
            ? json(409, { message: "会话已被其他窗口修改" })
            : json(200, { updatedAt: "2026-10-04T00:00:03.000Z" });
        }
        return json(404, { message: "not found" });
      }),
    );

    mocks.agent.isRunning = true;
    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "问题" })];
    const view = renderThread();
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    // 运行停止：flush #1（[u1]）→ load → 首次 PUT 409 → 进入「重读 → 合并 → 重试」，重读挂起
    mocks.agent.isRunning = false;
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(getMsgCount).toBe(3)); // flush #1 已到达（且停在了）重读步骤
    expect(puts).toEqual([{ status: 409, ids: ["u1"] }]);
    // 重读挂起期间消息更新：400ms 防抖发起 flush #2（[u1, a1]）并先完成落库
    mocks.agent.messages = [
      agentMessage({ id: "u1", role: "user", content: "问题" }),
      agentMessage({ id: "a1", role: "assistant", content: "回答" }),
    ];
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(puts).toHaveLength(2));
    expect(puts[1]).toEqual({ status: 200, ids: ["u1", "a1"] });
    // 放行 flush #1 的重读（响应早于 flush #2 落库生成）：已过期，不得再发起重试 PUT
    releaseFlush1Reload(json(200, { updatedAt: "2026-10-04T00:00:02.000Z", messages: [] }));
    await new Promise((r) => setTimeout(r, 300));
    expect(puts).toHaveLength(2);
    expect(serverIds).toEqual(["u1", "a1"]);
  });

  it("跨线程不误杀：t1 flush 在途时切 t2 并完成 t2 flush，t1 的 PUT 仍须落库（按线程序号）", async () => {
    // 审查发现的回归窗口（全局序号）：t1 的 flush 发起后其 loadMessages 挂起（弱网/后端
    // 停滞 0.5-1s 可达）；切 t2、回灌完成、t2 的 flush 发起并落库——此刻全局序号前进，t1
    // 的 flush 在写前被丢弃。但 t2 快照不含 t1 内容，且该 flush 是 t1 尾部（最坏一整轮 AI
    // 回答）最后一棒，切回 t1 回灌还会用缺尾部的服务端记录覆盖本地——静默永久丢失。
    // 守卫必须按 threadId 隔离：同线程旧快照作废，跨线程互不干扰。
    let t1GetCount = 0;
    let releaseT1FlushLoad!: (r: Response) => void;
    const t1FlushGate = new Promise<Response>((res) => {
      releaseT1FlushLoad = res;
    });
    const puts: Array<{ threadId: string; ids: string[] }> = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/api/conversations" && method === "GET")
          return json(200, [
            { id: "t1", title: "会话一", updatedAt: 2 },
            { id: "t2", title: "会话二", updatedAt: 1 },
          ]);
        const msgMatch = url.match(/^\/api\/conversations\/([^/]+)\/messages$/);
        if (msgMatch && method === "GET") {
          if (msgMatch[1] === "t1") {
            t1GetCount += 1;
            if (t1GetCount === 1) return json(200, { updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }); // t1 回灌
            return t1FlushGate; // t1 flush 的 loadMessages：挂起等放行
          }
          return json(200, { updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }); // t2 回灌与 flush load
        }
        if (msgMatch && method === "PUT") {
          const ids = (JSON.parse(String(init?.body)) as Array<{ id: string }>).map((m) => m.id);
          puts.push({ threadId: msgMatch[1], ids });
          return json(200, { updatedAt: "2026-10-04T00:00:03.000Z" });
        }
        return json(404, { message: "not found" });
      }),
    );

    mocks.agent.isRunning = true;
    mocks.agent.messages = [
      agentMessage({ id: "u1", role: "user", content: "t1 问题" }),
      agentMessage({ id: "a1", role: "assistant", content: "t1 回答" }),
    ];
    const view = render(
      <RuntimeProvider>
        <ThreadSwitchHarness targetId="t2" />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalledTimes(1)); // t1 回灌完成
    // 运行停止（isRunning true→false）：立即 flush t1 快照 [u1,a1]，其 loadMessages 挂起
    mocks.agent.isRunning = false;
    view.rerender(
      <RuntimeProvider>
        <ThreadSwitchHarness targetId="t2" />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(t1GetCount).toBe(2));
    // 切到 t2：回灌完成后新消息触发 t2 的 flush 并先完成落库
    fireEvent.click(screen.getByRole("button", { name: "切到 t2" }));
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalledTimes(2)); // t2 回灌完成
    mocks.agent.messages = [agentMessage({ id: "b1", role: "user", content: "t2 消息" })];
    view.rerender(
      <RuntimeProvider>
        <ThreadSwitchHarness targetId="t2" />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(puts).toEqual([{ threadId: "t2", ids: ["b1"] }]));
    // 放行 t1 的 loadMessages：t1 的 PUT 仍须被发起且内容为 t1 快照（跨线程不误杀）
    releaseT1FlushLoad(json(200, { updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }));
    await new Promise((r) => setTimeout(r, 300));
    expect(puts).toEqual([
      { threadId: "t2", ids: ["b1"] },
      { threadId: "t1", ids: ["u1", "a1"] },
    ]);
  });
});
