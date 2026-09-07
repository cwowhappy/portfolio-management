import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Message } from "@ag-ui/client";
import ThreadArea from "@/components/chat/ThreadArea";
import { RuntimeProvider, useChatRuntime } from "@/components/chat/RuntimeProvider";
import { installConversationsApi } from "@/tests/mockConversationsApi";

// ———— CopilotKit hooks mock ————

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
  useAgentProps: null as Record<string, unknown> | null,
  defaultToolRender: null as null | ((props: Record<string, unknown>) => React.ReactNode),
  renderToolCall: vi.fn(),
  interruptProps: null as { interrupts: unknown[]; resolve: (p: unknown, id?: string) => void } | null,
}));

vi.mock("@copilotkit/react-core/v2", () => ({
  useAgent: (props?: Record<string, unknown>) => {
    mocks.useAgentProps = props ?? null;
    return { agent: mocks.agent, isReady: mocks.isReady };
  },
  useCopilotKit: () => ({ copilotkit: { runAgent: mocks.runAgent } }),
  useDefaultRenderTool: ({ render }: { render: (p: Record<string, unknown>) => React.ReactNode }) => {
    mocks.defaultToolRender = render;
  },
  useInterrupt: (config: { render: (p: unknown) => React.ReactNode }) =>
    mocks.interruptProps ? config.render(mocks.interruptProps) : null,
  useRenderToolCall: () => mocks.renderToolCall,
  UseAgentUpdate: { OnMessagesChanged: "messages", OnRunStatusChanged: "run" },
}));

function agentMessage(m: Partial<Message> & { id: string }): Message {
  return m as Message;
}

function renderThread(llmReady: boolean | null = null) {
  return render(
    <RuntimeProvider>
      <ThreadArea llmReady={llmReady} />
    </RuntimeProvider>,
  );
}

/** 测试夹具：提供切换线程的入口（对应 Sidebar 的「切换会话」），让用例能真实驱动 RuntimeProvider.switchThread。 */
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
  mocks.useAgentProps = null;
  mocks.runAgent.mockReset();
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

describe("ThreadArea", () => {
  it("空会话渲染空状态与示例问题", async () => {
    renderThread();
    await waitFor(() => expect(screen.getByText("问行情 · 看走势 · 读财报")).toBeTruthy());
    expect(screen.getByText(/贵州茅台/)).toBeTruthy();
    expect(screen.getByText(/大盘表现/)).toBeTruthy();
    expect(screen.getByText(/宁德时代/)).toBeTruthy();
  });

  it("未配置 Key 时显示提示", async () => {
    renderThread(false);
    await waitFor(() => expect(screen.getByText(/未检测到 DEEPSEEK_API_KEY/)).toBeTruthy());
  });

  it("点击示例问题发送消息并启动 Agent", async () => {
    renderThread();
    await waitFor(() => expect(screen.getByText("问行情 · 看走势 · 读财报")).toBeTruthy());
    fireEvent.click(screen.getByText(/贵州茅台/));
    expect(mocks.agent.addMessage).toHaveBeenCalledWith({
      id: expect.any(String),
      role: "user",
      content: "帮我看看贵州茅台最近的走势和估值",
    });
    await waitFor(() => expect(mocks.runAgent).toHaveBeenCalled());
  });

  it("Agent 运行中点击示例不重复发送", async () => {
    mocks.agent.isRunning = true;
    renderThread();
    await waitFor(() => expect(screen.getByText("问行情 · 看走势 · 读财报")).toBeTruthy());
    fireEvent.click(screen.getByText(/贵州茅台/));
    expect(mocks.agent.addMessage).not.toHaveBeenCalled();
    expect(mocks.runAgent).not.toHaveBeenCalled();
  });

  it("AI 请求遇 401（如使用中被停用）触发 onUnauthorized 跳登录", async () => {
    const onUnauthorized = vi.fn();
    mocks.runAgent.mockRejectedValue(Object.assign(new Error("Unauthorized"), { status: 401 }));
    render(
      <RuntimeProvider>
        <ThreadArea llmReady={null} onUnauthorized={onUnauthorized} />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByText("问行情 · 看走势 · 读财报")).toBeTruthy());
    fireEvent.click(screen.getByText(/贵州茅台/));
    await waitFor(() => expect(onUnauthorized).toHaveBeenCalled());
    // 401 不当作普通错误横幅展示（应走登录跳转）
    expect(screen.queryByText(/Unauthorized/)).toBeNull();
  });

  it("非 401 错误仍展示 sendError 且不跳登录", async () => {
    const onUnauthorized = vi.fn();
    mocks.runAgent.mockRejectedValue(new Error("上游限流"));
    render(
      <RuntimeProvider>
        <ThreadArea llmReady={null} onUnauthorized={onUnauthorized} />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByText("问行情 · 看走势 · 读财报")).toBeTruthy());
    fireEvent.click(screen.getByText(/贵州茅台/));
    expect(await screen.findByText(/上游限流/)).toBeTruthy();
    expect(onUnauthorized).not.toHaveBeenCalled();
  });

  it("渲染用户消息", async () => {
    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "你好" })];
    renderThread();
    await waitFor(() => expect(screen.getByText("你好")).toBeTruthy());
  });

  it("渲染助手消息：Markdown 正文 + 工具调用 + 反馈按钮", async () => {
    mocks.agent.messages = [
      agentMessage({
        id: "a1",
        role: "assistant",
        content: "结论：**上涨**",
        toolCalls: [{ id: "tc1", type: "function", function: { name: "get_quote", arguments: "{}" } }],
      }),
    ];
    renderThread();
    await waitFor(() => expect(screen.getByText("结论：")).toBeTruthy());
    expect(screen.getByText("上涨").tagName).toBe("STRONG");
    expect(screen.getByTestId("tool-rendered")).toBeTruthy();
    expect(mocks.renderToolCall).toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "回答有帮助" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "回答需要改进" })).toBeTruthy();
  });

  it("渲染围栏代码块与行内代码", async () => {
    mocks.agent.messages = [
      agentMessage({
        id: "a1",
        role: "assistant",
        content: "示例：\n\n```js\nconst a = 1;\n```\n\n行内 `npm install` 结束",
      }),
    ];
    renderThread();
    await waitFor(() => expect(screen.getByText("js")).toBeTruthy());
    expect(screen.getByText("npm install").tagName).toBe("CODE");
  });

  it("渲染推理消息与思考过程", async () => {
    mocks.agent.messages = [
      agentMessage({ id: "r1", role: "reasoning", content: "先查代码再查行情" }),
    ];
    renderThread();
    await waitFor(() => expect(screen.getByText("思考过程（8 字）")).toBeTruthy());
    expect(screen.getByText("先查代码再查行情")).toBeTruthy();
  });

  it("空推理内容不渲染思考过程", async () => {
    mocks.agent.messages = [agentMessage({ id: "r1", role: "reasoning", content: "" })];
    renderThread();
    await waitFor(() => expect(screen.queryByText(/思考过程/)).toBeNull());
  });

  it("忽略未知角色消息", async () => {
    mocks.agent.messages = [agentMessage({ id: "s1", role: "system", content: "系统" })];
    const { container } = renderThread();
    await waitFor(() => expect(container.querySelector(".animate-rise")).toBeNull());
  });

  it("Agent 就绪后回灌服务端历史", async () => {
    api = installConversationsApi({
      list: [{ id: "t1", title: "历史", updatedAt: 2 }],
      messages: { t1: [{ id: "m1", role: "user", content: "历史问题", createdAt: 1 }] },
    });
    renderThread();
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    const arg = mocks.agent.setMessages.mock.calls[0][0] as Message[];
    expect(arg).toEqual([{ id: "m1", role: "user", content: "历史问题" }]);
  });

  it("消息变化后持久化到服务端", async () => {
    api = installConversationsApi({
      list: [{ id: "t1", title: "会话", updatedAt: 2 }],
    });
    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "新消息" })];
    renderThread();
    await waitFor(() => {
      expect(api.state.messages.get("t1")).toContainEqual(
        expect.objectContaining({ id: "u1", role: "user", content: "新消息" }),
      );
    });
  });

  it("持久化前先快照消息，避免 await 期间切线程串写", async () => {
    // 自定义 mock：第一次 GET /messages（历史回灌）立即返回空；第二次（持久化的 loadMessages）挂起，
    // 以此模拟 await 窗口内 agent.messages 被切线程替换的竞态。
    let getCount = 0;
    let resolveGate!: (r: Response) => void;
    const gate = new Promise<Response>((res) => {
      resolveGate = res;
    });
    const putBodies: Array<Array<{ content: string }>> = [];
    const json = (body: unknown) =>
      new Response(JSON.stringify(body), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    const noContent = () => new Response(null, { status: 204 });
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/api/conversations" && method === "GET")
          return json([{ id: "t1", title: "会话", updatedAt: 2 }]);
        if (url === "/api/conversations" && method === "POST")
          return json({ id: "x", title: "新会话", updatedAt: 3 });
        if (url === "/api/conversations/t1/messages" && method === "GET") {
          getCount += 1;
          if (getCount === 1) return json([]); // 历史回灌立即返回
          return gate; // 持久化的 loadMessages 挂起
        }
        if (url === "/api/conversations/t1/messages" && method === "PUT") {
          putBodies.push(JSON.parse(String(init?.body)) as Array<{ content: string }>);
          return noContent();
        }
        return json({ message: "not found" });
      }),
    );

    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "旧线程消息" })];
    renderThread();
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    // 等持久化 IIFE 发起 loadMessages 并挂起（getCount === 2）
    await waitFor(() => expect(getCount).toBe(2));
    // 模拟切线程 + 历史回灌把 agent.messages 替换成新线程消息
    mocks.agent.messages = [agentMessage({ id: "u2", role: "user", content: "新线程消息" })];
    resolveGate(json([])); // 放行持久化的 loadMessages
    await waitFor(() => expect(putBodies.length).toBeGreaterThan(0));
    // PUT body 必须使用 await 前快照（旧线程消息），而非被替换后的新线程消息
    const firstPut = putBodies[0];
    expect(firstPut.map((m) => m.content)).toEqual(["旧线程消息"]);
    expect(firstPut.map((m) => m.content)).not.toContain("新线程消息");
  });

  it("慢历史回灌时切线程：不会把旧线程消息 PUT 到新线程（防抖跨线程串写）", async () => {
    // 场景：线程 t1 已有消息；切到 t2，但 t2 的历史回灌 GET 挂起（慢于 400ms 防抖窗口）。
    // 若防抖持久化把 agent.messages（仍是 t1 内容）绑定到新 threadId=t2，放行后会把旧会话内容 PUT 覆盖 t2 服务端记录。
    let t2GetCount = 0;
    let resolveGate!: (r: Response) => void;
    const gate = new Promise<Response>((res) => { resolveGate = res; });
    const json = (body: unknown) =>
      new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } });
    const noContent = () => new Response(null, { status: 204 });
    const putBodies: Record<string, Array<Array<{ content: string }>>> = { t1: [], t2: [] };
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
        const url = String(input);
        const method = init?.method ?? "GET";
        if (url === "/api/conversations" && method === "GET")
          return json([
            { id: "t1", title: "会话1", updatedAt: 2 },
            { id: "t2", title: "会话2", updatedAt: 1 },
          ]);
        if (url === "/api/conversations/t1/messages" && method === "GET")
          return json([{ id: "a1", role: "user", content: "旧线程消息", createdAt: 1 }]);
        if (url === "/api/conversations/t2/messages" && method === "GET") {
          t2GetCount += 1;
          return gate; // 慢回灌：历史请求挂起（模拟 >400ms 或失败）
        }
        const putMatch = url.match(/^\/api\/conversations\/([^/]+)\/messages$/);
        if (putMatch && method === "PUT") {
          putBodies[putMatch[1]].push(JSON.parse(String(init?.body)) as Array<{ content: string }>);
          return noContent();
        }
        return json({ message: "not found" });
      }),
    );

    mocks.agent.messages = [agentMessage({ id: "a1", role: "user", content: "旧线程消息" })];
    render(
      <RuntimeProvider>
        <ThreadSwitchHarness targetId="t2" />
      </RuntimeProvider>,
    );
    // 等 Provider 就绪且 t1 历史回灌完成
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    // 切到 t2：t2 回灌 GET 挂起
    fireEvent.click(screen.getByRole("button", { name: "切到 t2" }));
    await waitFor(() => expect(t2GetCount).toBe(1));
    // 若存在跨线程串写，400ms 防抖会把旧线程快照绑定到 t2 并额外发起 GET；这里给它足够时间触发
    await new Promise((r) => setTimeout(r, 550));
    // 放行 t2 慢回灌
    resolveGate(json([]));
    // 等放行后的微任务 / 可能的 PUT 完成
    await new Promise((r) => setTimeout(r, 150));
    // 双保险断言：(1) 只应有一次 t2 历史回灌 GET；(2) 不得向 t2 写入任何旧线程消息
    expect(t2GetCount).toBe(1);
    expect(putBodies.t2).toHaveLength(0);
  });

  it("无消息时不调用持久化接口", async () => {
    renderThread();
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    const putCalls = api.fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT");
    expect(putCalls).toHaveLength(0);
  });

  it("useAgent 传 throttleMs: 150（流式期间合并高频更新，降低重渲染）", async () => {
    renderThread();
    await waitFor(() => expect(mocks.useAgentProps).toBeTruthy());
    expect(mocks.useAgentProps).toMatchObject({ agentId: "invest", throttleMs: 150 });
  });

  it("isRunning 由 true→false 时立即 flush 尾部内容（不等 400ms 防抖）", async () => {
    vi.useFakeTimers();
    api = installConversationsApi({ list: [{ id: "t1", title: "会话", updatedAt: 2 }] });
    mocks.agent.isRunning = true;
    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "问题" })];
    const view = renderThread();
    await act(async () => {});
    // 流式尾部内容到来，防抖窗口（400ms）尚未到期
    mocks.agent.messages = [
      agentMessage({ id: "u1", role: "user", content: "问题" }),
      agentMessage({ id: "a1", role: "assistant", content: "尾部回答" }),
    ];
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await act(async () => {});
    expect(api.state.messages.get("t1") ?? []).toHaveLength(0);
    // 运行结束：立即落库，不再等防抖
    mocks.agent.isRunning = false;
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await act(async () => {});
    expect(api.state.messages.get("t1")).toContainEqual(
      expect.objectContaining({ id: "a1", role: "assistant", content: "尾部回答" }),
    );
  });

  it("卸载时防抖窗口内的 pending 写入以 keepalive 完成 flush", async () => {
    vi.useFakeTimers();
    api = installConversationsApi({ list: [{ id: "t1", title: "会话", updatedAt: 2 }] });
    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "未落库消息" })];
    const view = renderThread();
    await act(async () => {});
    // 防抖未到期，尚未 PUT
    expect(
      api.fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT"),
    ).toHaveLength(0);
    view.unmount();
    await act(async () => {});
    const putCalls = api.fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT");
    expect(putCalls.length).toBeGreaterThan(0);
    expect((putCalls[0][1] as RequestInit).keepalive).toBe(true);
    expect(api.state.messages.get("t1")).toContainEqual(
      expect.objectContaining({ id: "u1", role: "user", content: "未落库消息" }),
    );
  });

  it("默认工具渲染器渲染 ToolCallCard", async () => {
    renderThread();
    await waitFor(() => expect(mocks.defaultToolRender).toBeTruthy());
    const node = mocks.defaultToolRender!({
      name: "get_quote",
      toolCallId: "c1",
      parameters: { code: "600519" },
      status: "complete",
      result: '{"pe":19.95}',
    });
    const { getByText } = render(<>{node}</>);
    expect(getByText("实时行情")).toBeTruthy();
  });

  it("权限中断：卡片渲染且批准/拒绝 resolve 携带 interruptId（FR-5）", async () => {
    const resolve = vi.fn();
    mocks.interruptProps = {
      interrupts: [
        {
          id: "reply-1:call_a",
          message: "需要确认后执行",
          metadata: { toolName: "write_note", toolInput: '{"file":"a.md"}' },
        },
      ],
      resolve,
    };
    renderThread();
    await waitFor(() => expect(screen.getByText(/write_note/)).toBeTruthy());
    expect(screen.getByText("需要确认后执行")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    expect(resolve).toHaveBeenCalledWith({ approved: true }, "reply-1:call_a");

    fireEvent.click(screen.getByRole("button", { name: "拒绝" }));
    expect(resolve).toHaveBeenCalledWith({ approved: false }, "reply-1:call_a");
  });

  it("多中断铺开：两个 interrupt 渲染两张卡片，resolve 各自携带 interruptId（FR-5）", async () => {
    const resolve = vi.fn();
    mocks.interruptProps = {
      interrupts: [
        {
          id: "reply-1:call_a",
          message: "写入前请确认 A",
          metadata: { toolName: "write_note", toolInput: '{"file":"a.md"}' },
        },
        {
          id: "reply-2:call_b",
          message: "写入前请确认 B",
          metadata: { toolName: "delete_record", toolInput: '{"id":42}' },
        },
      ],
      resolve,
    };
    renderThread();
    // 两张卡片都渲染（不同工具名与 message 各自可见）
    await waitFor(() => expect(screen.getByText(/write_note/)).toBeTruthy());
    expect(screen.getByText(/delete_record/)).toBeTruthy();
    expect(screen.getByText("写入前请确认 A")).toBeTruthy();
    expect(screen.getByText("写入前请确认 B")).toBeTruthy();

    // 两组批准/拒绝按钮（DOM 顺序与 interrupts 数组一致）
    const approveButtons = screen.getAllByRole("button", { name: "批准" });
    const denyButtons = screen.getAllByRole("button", { name: "拒绝" });
    expect(approveButtons).toHaveLength(2);
    expect(denyButtons).toHaveLength(2);

    fireEvent.click(approveButtons[0]);
    fireEvent.click(denyButtons[1]);
    // 各自按钮 resolve 携带各自的 interruptId
    expect(resolve).toHaveBeenCalledWith({ approved: true }, "reply-1:call_a");
    expect(resolve).toHaveBeenCalledWith({ approved: false }, "reply-2:call_b");
  });

  it("契约错误翻译：中断失效报错显示中文引导（FR-8）", async () => {
    mocks.runAgent.mockRejectedValue(
      new Error(
        "Thread has unresolved interrupts; RunAgentInput.resume must address all of them",
      ),
    );
    renderThread();
    await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());
    const ta = screen.getByPlaceholderText(composerPlaceholder);
    fireEvent.change(ta, { target: { value: "继续" } });
    fireEvent.keyDown(ta, { key: "Enter", shiftKey: false });
    await waitFor(() =>
      expect(
        screen.getByText(
          "当前对话有未完成的审批（可能因页面刷新或服务重启失效），请开启新对话继续。",
        ),
      ).toBeTruthy(),
    );
  });

  // FR-8 终审修复：契约错误以流内 RUN_ERROR SSE 事件到达，@ag-ui/client 0.0.59 对其
  // resolve（而非 reject）runAgent 的 promise —— catch 出口不可达，须经 agent 订阅出口透出。
  function lastAgentSubscriber() {
    const calls = mocks.agent.subscribe.mock.calls;
    expect(calls.length).toBeGreaterThan(0);
    return calls[calls.length - 1][0] as {
      onRunErrorEvent: (params: {
        event: { type: string; message: string; code?: string };
      }) => void;
    };
  }

  it("流内 RUN_ERROR 契约错误事件（code 优先匹配）显示中文引导横幅（FR-8）", async () => {
    renderThread();
    await waitFor(() => expect(mocks.agent.subscribe).toHaveBeenCalled());
    act(() => {
      lastAgentSubscriber().onRunErrorEvent({
        event: {
          type: "RUN_ERROR",
          message: "Thread has unresolved interrupts; RunAgentInput.resume must address all of them",
          code: "AGUI_INTERRUPT_CONTRACT_ERROR",
        },
      });
    });
    expect(
      await screen.findByText(
        "当前对话有未完成的审批（可能因页面刷新或服务重启失效），请开启新对话继续。",
      ),
    ).toBeTruthy();
  });

  it("流内 RUN_ERROR 非契约错误显示其 message（FR-8）", async () => {
    renderThread();
    await waitFor(() => expect(mocks.agent.subscribe).toHaveBeenCalled());
    act(() => {
      lastAgentSubscriber().onRunErrorEvent({
        event: { type: "RUN_ERROR", message: "上游模型超时", code: "UPSTREAM_TIMEOUT" },
      });
    });
    expect(await screen.findByText("上游模型超时")).toBeTruthy();
  });

  it("流内 RUN_ERROR 用户主动停止（code=abort）不弹错误横幅（FR-8）", async () => {
    renderThread();
    await waitFor(() => expect(mocks.agent.subscribe).toHaveBeenCalled());
    act(() => {
      lastAgentSubscriber().onRunErrorEvent({
        event: { type: "RUN_ERROR", message: "This operation was aborted", code: "abort" },
      });
    });
    await act(async () => {});
    expect(screen.queryByText(/This operation was aborted/)).toBeNull();
    expect(screen.queryByRole("button", { name: "关闭错误提示" })).toBeNull();
  });

  describe("Composer", () => {
    it("输入文本后点击发送", async () => {
      renderThread();
      await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());
      fireEvent.change(screen.getByPlaceholderText(composerPlaceholder), {
        target: { value: " 你好 " },
      });
      fireEvent.click(screen.getByRole("button", { name: "发送" }));
      expect(mocks.agent.addMessage).toHaveBeenCalledWith({
        id: expect.any(String),
        role: "user",
        content: "你好",
      });
      await waitFor(() => expect(mocks.runAgent).toHaveBeenCalled());
      expect((screen.getByPlaceholderText(composerPlaceholder) as HTMLTextAreaElement).value).toBe("");
    });

    it("空白输入禁用发送按钮", async () => {
      renderThread();
      await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());
      const btn = screen.getByRole("button", { name: "发送" }) as HTMLButtonElement;
      expect(btn.disabled).toBe(true);
      fireEvent.click(btn);
      expect(mocks.agent.addMessage).not.toHaveBeenCalled();
    });

    it("Enter 发送，Shift+Enter 换行", async () => {
      renderThread();
      await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());
      const ta = screen.getByPlaceholderText(composerPlaceholder);
      fireEvent.change(ta, { target: { value: "问题" } });
      fireEvent.keyDown(ta, { key: "Enter", shiftKey: false });
      expect(mocks.agent.addMessage).toHaveBeenCalledWith({
        id: expect.any(String),
        role: "user",
        content: "问题",
      });
      mocks.agent.addMessage.mockClear();
      fireEvent.change(ta, { target: { value: "换行问题" } });
      fireEvent.keyDown(ta, { key: "Enter", shiftKey: true });
      expect(mocks.agent.addMessage).not.toHaveBeenCalled();
    });

    it("运行中显示停止按钮并中止运行", async () => {
      mocks.agent.isRunning = true;
      renderThread();
      await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());
      fireEvent.click(screen.getByRole("button", { name: "■ 停止" }));
      expect(mocks.agent.abortRun).toHaveBeenCalled();
      expect(screen.queryByRole("button", { name: "发送" })).toBeNull();
    });

    it("运行中 Enter 不发送", async () => {
      mocks.agent.isRunning = true;
      renderThread();
      await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());
      const ta = screen.getByPlaceholderText(composerPlaceholder);
      fireEvent.change(ta, { target: { value: "问题" } });
      fireEvent.keyDown(ta, { key: "Enter", shiftKey: false });
      expect(mocks.agent.addMessage).not.toHaveBeenCalled();
    });
  });

  describe("FeedbackBar", () => {
    async function renderWithAssistant() {
      mocks.agent.messages = [
        agentMessage({ id: "a1", role: "assistant", content: "回答内容" }),
      ];
      renderThread();
      await waitFor(() => expect(screen.getByText("回答内容")).toBeTruthy());
    }

    it("点赞后写入 localStorage 并高亮", async () => {
      await renderWithAssistant();
      fireEvent.click(screen.getByRole("button", { name: "回答有帮助" }));
      await waitFor(() => {
        const raw = localStorage.getItem("invest.feedback.a1");
        expect(raw).toContain('"positive"');
      });
      expect(screen.getByRole("button", { name: "回答有帮助" }).getAttribute("aria-pressed")).toBe(
        "true",
      );
    });

    it("点踩写入 negative", async () => {
      await renderWithAssistant();
      fireEvent.click(screen.getByRole("button", { name: "回答需要改进" }));
      await waitFor(() =>
        expect(localStorage.getItem("invest.feedback.a1")).toContain('"negative"'),
      );
    });

    it("已有反馈时初始即高亮", async () => {
      localStorage.setItem("invest.feedback.a1", JSON.stringify({ type: "positive" }));
      await renderWithAssistant();
      await waitFor(() =>
        expect(screen.getByRole("button", { name: "回答有帮助" }).getAttribute("aria-pressed")).toBe(
          "true",
        ),
      );
    });

    it("损坏的反馈数据被忽略", async () => {
      localStorage.setItem("invest.feedback.a1", "{broken");
      await renderWithAssistant();
      expect(screen.getByRole("button", { name: "回答有帮助" }).getAttribute("aria-pressed")).toBe(
        "false",
      );
    });

    it("反馈键达到容量上限时清掉最旧的再写入", async () => {
      // 预置 200 条（上限），m0 最旧
      for (let i = 0; i < 200; i++) {
        localStorage.setItem(`invest.feedback.m${i}`, JSON.stringify({ type: "positive", at: i }));
      }
      await renderWithAssistant();
      fireEvent.click(screen.getByRole("button", { name: "回答有帮助" }));
      expect(localStorage.getItem("invest.feedback.m0")).toBeNull();
      expect(localStorage.getItem("invest.feedback.m1")).not.toBeNull();
      expect(localStorage.getItem("invest.feedback.a1")).toContain('"positive"');
    });
  });
});
