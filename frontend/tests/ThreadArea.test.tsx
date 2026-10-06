import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Message } from "@ag-ui/client";
import ThreadArea from "@/components/chat/ThreadArea";
import { RuntimeProvider, useChatRuntime } from "@/components/chat/RuntimeProvider";
import { installConversationsApi } from "@/tests/mockConversationsApi";
import { TRUST_ANCHORS_EVENT, TRUST_CORRECTION_EVENT, trustStore } from "@/lib/trustMeta";

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
  chatConfigProps: null as Record<string, unknown> | null,
  defaultToolRender: null as null | ((props: Record<string, unknown>) => React.ReactNode),
  renderToolCall: vi.fn(),
  interruptProps: null as { interrupts: unknown[]; resolve: (p: unknown, id?: string) => void } | null,
  interruptConfig: null as Record<string, unknown> | null,
}));

vi.mock("@copilotkit/react-core/v2", () => ({
  // RuntimeProvider 的 threadId 绑定探针（issue #26）：捕获 chat 配置 provider 的 props
  CopilotChatConfigurationProvider: ({
    children,
    ...props
  }: {
    children: React.ReactNode;
  } & Record<string, unknown>) => {
    mocks.chatConfigProps = props;
    return children;
  },
  useAgent: (props?: Record<string, unknown>) => {
    mocks.useAgentProps = props ?? null;
    return { agent: mocks.agent, isReady: mocks.isReady };
  },
  useCopilotKit: () => ({ copilotkit: { runAgent: mocks.runAgent } }),
  useDefaultRenderTool: ({ render }: { render: (p: Record<string, unknown>) => React.ReactNode }) => {
    mocks.defaultToolRender = render;
  },
  useInterrupt: (config: { render: (p: unknown) => React.ReactNode }) => {
    mocks.interruptConfig = config;
    return mocks.interruptProps ? config.render(mocks.interruptProps) : null;
  },
  useRenderToolCall: () => mocks.renderToolCall,
  // ChartToolRenderers（图表具名渲染器）挂进 ToolCallRenderers 后，模块 mock 需补该导出（无操作即可）
  useRenderTool: () => {},
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
  mocks.chatConfigProps = null;
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
  mocks.interruptConfig = null;
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
    // issue #27 闸门：发送需等当前线程回灌完成（setMessages 被调即回灌落地）
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
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

  it("历史回灌完成前发送被闸门拦住，回灌完成后放行且全程不掐流（issue #27）", async () => {
    // 历史回灌 GET 挂起，模拟 dev 首次编译 >1.5s 窗口内用户登录后立发消息：
    // 现状 isReady 即放行 → runAgent 在途时回灌完成 → abortRun() 掐断流 + 消息被清空。
    let resolveHydrate!: (r: Response) => void;
    const hydrateGate = new Promise<Response>((res) => {
      resolveHydrate = res;
    });
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
        if (url === "/api/conversations" && method === "POST")
          return json({ id: "x", title: "新会话", updatedAt: 3 });
        if (url === "/api/conversations/t1/messages" && method === "GET") return hydrateGate;
        if (url === "/api/conversations/t1/messages" && method === "PUT")
          return json({ updatedAt: "2026-10-04T00:00:01.000Z" });
        return json({ message: "not found" });
      }),
    );

    renderThread();
    await waitFor(() => expect(screen.getByText("问行情 · 看走势 · 读财报")).toBeTruthy());
    // 回灌未完成：发送须被前置闸门拦住
    fireEvent.click(screen.getByText(/贵州茅台/));
    expect(mocks.agent.addMessage).not.toHaveBeenCalled();
    expect(mocks.runAgent).not.toHaveBeenCalled();
    // 放行回灌（空历史）→ 闸门开启
    resolveHydrate(json({ updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }));
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    // 回灌完成后同一消息可正常发送
    fireEvent.click(screen.getByText(/贵州茅台/));
    await waitFor(() => expect(mocks.runAgent).toHaveBeenCalled());
    // 竞态根源路径（回灌完成即 abortRun 在途运行）全程不得出现
    expect(mocks.agent.abortRun).not.toHaveBeenCalled();
  });

  it("chat 配置 threadId 绑定会话表 id，切线程同步（issue #26：AG-UI 线程与会话解耦修复）", async () => {
    api = installConversationsApi({
      list: [
        { id: "t1", title: "会话一", updatedAt: 2 },
        { id: "t2", title: "会话二", updatedAt: 1 },
      ],
    });
    render(
      <RuntimeProvider>
        <ThreadSwitchHarness targetId="t2" />
      </RuntimeProvider>,
    );
    // 挂载后选中列表第一项 t1 → chat 配置 threadId 必须等于会话表 id（而非自铸 UUID）
    await waitFor(() => expect(mocks.chatConfigProps?.threadId).toBe("t1"));
    // 切到 t2 → 配置 threadId 同步切换
    fireEvent.click(screen.getByText("切到 t2"));
    await waitFor(() => expect(mocks.chatConfigProps?.threadId).toBe("t2"));
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
    // issue #27 闸门：发送需等当前线程回灌完成（setMessages 被调即回灌落地）
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
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
    // issue #27 闸门：发送需等当前线程回灌完成（setMessages 被调即回灌落地）
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
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

  // ———— P2-F2：会话回灌失败可操作错误态 ————

  /**
   * 回灌失败夹具：GET /api/conversations/:id/messages 对 failThreads 登记的线程先返回 N 次 500
   * （loadMessages 经 request() 对非 2xx 抛错），此后及未登记线程正常返回空历史。
   * 「新建会话」产生的新线程 GET 必须成功，避免错误卡回弹干扰断言。
   */
  function installHydrateFlakyApi(failThreads: Record<string, number>) {
    const failLeft = { ...failThreads };
    const json = (status: number, body: unknown) =>
      new Response(JSON.stringify(body), {
        status,
        headers: { "Content-Type": "application/json" },
      });
    const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method ?? "GET";
      if (url === "/api/conversations" && method === "GET")
        return json(200, [{ id: "t1", title: "会话", updatedAt: 2 }]);
      if (url === "/api/conversations" && method === "POST")
        return json(201, { id: "n1", title: "新会话", updatedAt: 3 });
      const msgMatch = url.match(/^\/api\/conversations\/([^/]+)\/messages$/);
      if (msgMatch && method === "GET") {
        const id = msgMatch[1];
        if ((failLeft[id] ?? 0) > 0) {
          failLeft[id] -= 1;
          return json(500, { message: "服务器开小差" });
        }
        return json(200, { updatedAt: "2026-10-04T00:00:00.000Z", messages: [] });
      }
      if (msgMatch && method === "PUT") return json(200, { updatedAt: "2026-10-04T00:00:01.000Z" });
      return json(404, { message: "not found" });
    });
    vi.stubGlobal("fetch", fetchMock);
    return { fetchMock };
  }

  it("回灌失败：错误卡可见（重试/新建会话），Composer 仍禁发（闸门语义不变）", async () => {
    installHydrateFlakyApi({ t1: 1 });
    renderThread();
    const card = await screen.findByTestId("hydrate-error");
    expect(card.textContent).toContain("会话历史加载失败");
    expect(screen.getByRole("button", { name: "重试" })).toBeTruthy();
    expect(screen.getByRole("button", { name: "新建会话" })).toBeTruthy();
    // 闸门不放行：有输入草稿时发送按钮仍禁用、点击/回车均不得发起运行
    fireEvent.change(screen.getByPlaceholderText(composerPlaceholder), {
      target: { value: "试试能不能发" },
    });
    const sendBtn = screen.getByRole("button", { name: "发送" }) as HTMLButtonElement;
    expect(sendBtn.disabled).toBe(true);
    fireEvent.click(sendBtn);
    expect(mocks.agent.addMessage).not.toHaveBeenCalled();
    expect(mocks.runAgent).not.toHaveBeenCalled();
  });

  it("点「重试」：第二次回灌成功 → 错误卡消失、Composer 放行", async () => {
    installHydrateFlakyApi({ t1: 1 });
    renderThread();
    await screen.findByTestId("hydrate-error");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    // 第二次 loadMessages 成功 → setMessages 落地（首次失败从未触达）
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(screen.queryByTestId("hydrate-error")).toBeNull());
    // 回灌完成后闸门开启：输入后可发送
    fireEvent.change(screen.getByPlaceholderText(composerPlaceholder), {
      target: { value: "你好" },
    });
    const sendBtn = screen.getByRole("button", { name: "发送" }) as HTMLButtonElement;
    expect(sendBtn.disabled).toBe(false);
    fireEvent.click(sendBtn);
    await waitFor(() => expect(mocks.runAgent).toHaveBeenCalled());
  });

  it("点「新建会话」：newThread 生效（POST /api/conversations）、错误卡消失", async () => {
    const api = installHydrateFlakyApi({ t1: 1 });
    renderThread();
    await screen.findByTestId("hydrate-error");
    fireEvent.click(screen.getByRole("button", { name: "新建会话" }));
    // newThread 的可观察行为：挂载时列表非空不会 POST，此处 POST 即新建会话被触发
    const posts = () =>
      api.fetchMock.mock.calls.filter(
        ([input, init]) => String(input) === "/api/conversations" && init?.method === "POST",
      );
    await waitFor(() => expect(posts()).toHaveLength(1));
    await waitFor(() => expect(screen.queryByTestId("hydrate-error")).toBeNull());
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

  it("PUT 遇 409：GET 服务端消息后按 id 合并重试一次，最终落库为并集", async () => {
    // B6 并发写：服务端已有其他窗口写入的 s1；本窗口防抖 PUT 首次撞 409（乐观校验冲突），
    // flushPersist 须重新 GET 并与之 union 后重试，而不是用本地快照整体覆盖（互删）。
    api = installConversationsApi({
      list: [{ id: "t1", title: "会话", updatedAt: 2 }],
      messages: { t1: [{ id: "s1", role: "user", content: "其他窗口消息", createdAt: 9 }] },
      putConflictTimes: 1,
    });
    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "本地消息" })];
    renderThread();
    await waitFor(() => {
      expect(api.state.messages.get("t1")?.map((m) => m.id)).toEqual(["s1", "u1"]);
    });
    // 服务端消息保留原 createdAt；重试成功后共有消息不重复
    expect(api.state.messages.get("t1")).toEqual([
      { id: "s1", role: "user", content: "其他窗口消息", createdAt: 9 },
      expect.objectContaining({ id: "u1", role: "user", content: "本地消息" }),
    ]);
    // 恰好两次 PUT：首次 409 + 合并后重试一次成功，不无限重试
    const putCalls = api.fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT");
    expect(putCalls).toHaveLength(2);
  });

  it("重试仍 409：显示「请刷新确认」横幅，本地消息不丢，且不再重试", async () => {
    api = installConversationsApi({
      list: [{ id: "t1", title: "会话", updatedAt: 2 }],
      messages: { t1: [] },
      putConflictTimes: 2,
    });
    mocks.agent.messages = [agentMessage({ id: "u1", role: "user", content: "本地草稿" })];
    renderThread();
    // 用户可见横幅提示（本地草稿消息仍渲染、不被清除）
    expect(await screen.findByText("会话在其他窗口被修改，请刷新确认")).toBeTruthy();
    expect(screen.getByText("本地草稿")).toBeTruthy();
    // 仅重试一次：两次 409 后停止，不会第三次 PUT
    await waitFor(() => {
      const putCalls = api.fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT");
      expect(putCalls).toHaveLength(2);
    });
    await new Promise((r) => setTimeout(r, 500));
    const putCalls = api.fetchMock.mock.calls.filter(([, init]) => init?.method === "PUT");
    expect(putCalls).toHaveLength(2);
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
          if (getCount === 1) return json({ updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }); // 历史回灌立即返回
          return gate; // 持久化的 loadMessages 挂起
        }
        if (url === "/api/conversations/t1/messages" && method === "PUT") {
          putBodies.push(JSON.parse(String(init?.body)) as Array<{ content: string }>);
          return json({ updatedAt: "2026-10-04T00:00:01.000Z" });
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
    resolveGate(json({ updatedAt: "2026-10-04T00:00:00.000Z", messages: [] })); // 放行持久化的 loadMessages
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
          return json({
            updatedAt: "2026-10-04T00:00:00.000Z",
            messages: [{ id: "a1", role: "user", content: "旧线程消息", createdAt: 1 }],
          });
        if (url === "/api/conversations/t2/messages" && method === "GET") {
          t2GetCount += 1;
          return gate; // 慢回灌：历史请求挂起（模拟 >400ms 或失败）
        }
        const putMatch = url.match(/^\/api\/conversations\/([^/]+)\/messages$/);
        if (putMatch && method === "PUT") {
          putBodies[putMatch[1]].push(JSON.parse(String(init?.body)) as Array<{ content: string }>);
          return json({ updatedAt: "2026-10-04T00:00:01.000Z" });
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
    resolveGate(json({ updatedAt: "2026-10-04T00:00:00.000Z", messages: [] }));
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

  it("useInterrupt 显式绑定 invest agent：缺省会解析到不存在的 default agent 导致页面崩溃（e2e 回归钉）", async () => {
    renderThread();
    await waitFor(() => expect(mocks.interruptConfig).not.toBeNull());
    expect(mocks.interruptConfig?.agentId).toBe("invest");
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
    const view = renderThread();
    await waitFor(() => expect(screen.getByText(/write_note/)).toBeTruthy());
    expect(screen.getByText("需要确认后执行")).toBeTruthy();

    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    expect(resolve).toHaveBeenCalledWith({ approved: true }, "reply-1:call_a");

    // FR-5 补全：单卡点击后即入已处理态（按钮收起），同卡不可再改选；
    // 拒绝路径重新挂载后验证（断言意图不变：resolve 携带 interruptId）
    view.unmount();
    const view2 = renderThread();
    await waitFor(() => expect(screen.getByRole("button", { name: "拒绝" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "拒绝" }));
    expect(resolve).toHaveBeenCalledWith({ approved: false }, "reply-1:call_a");
    view2.unmount();
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

  it("多审批卡逐张反馈：单卡点击即显已处理态，其余卡仍可点（FR-5 补全）", async () => {
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
    await waitFor(() =>
      expect(screen.getAllByRole("button", { name: "批准" })).toHaveLength(2),
    );

    // 点第一张「批准」→ 第一张变为已批准态（文案在、按钮不在），第二张仍可点
    fireEvent.click(screen.getAllByRole("button", { name: "批准" })[0]);
    expect(resolve).toHaveBeenCalledWith({ approved: true }, "reply-1:call_a");
    await waitFor(() => expect(screen.getByText("已批准，等待其余确认…")).toBeTruthy());
    // 仅剩第二张的批准/拒绝按钮；第二张内容不受影响
    expect(screen.getAllByRole("button", { name: "批准" })).toHaveLength(1);
    expect(screen.getAllByRole("button", { name: "拒绝" })).toHaveLength(1);
    expect(screen.getByText(/delete_record/)).toBeTruthy();
    expect(screen.getByText("写入前请确认 B")).toBeTruthy();

    // 再点第二张「拒绝」→ 两张均为已处理态，按钮全部收起
    fireEvent.click(screen.getAllByRole("button", { name: "拒绝" })[0]);
    expect(resolve).toHaveBeenCalledWith({ approved: false }, "reply-2:call_b");
    await waitFor(() => expect(screen.getByText("已拒绝，等待其余确认…")).toBeTruthy());
    expect(screen.getByText("已批准，等待其余确认…")).toBeTruthy();
    expect(screen.queryByRole("button", { name: "批准" })).toBeNull();
    expect(screen.queryByRole("button", { name: "拒绝" })).toBeNull();
  });

  it("interrupts 换新一轮：旧已处理标记不残留到新卡（状态清理）", async () => {
    const resolve = vi.fn();
    mocks.interruptProps = {
      interrupts: [
        {
          id: "reply-1:call_a",
          message: "写入前请确认 A",
          metadata: { toolName: "write_note", toolInput: '{"file":"a.md"}' },
        },
      ],
      resolve,
    };
    const view = renderThread();
    await waitFor(() => expect(screen.getByRole("button", { name: "批准" })).toBeTruthy());
    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    await waitFor(() => expect(screen.getByText("已批准，等待其余确认…")).toBeTruthy());

    // 全部应答已提交续跑，新一轮中断到来（不同 id）：旧卡/旧已处理态消失，新卡可正常审批
    mocks.interruptProps = {
      interrupts: [
        {
          id: "reply-9:call_x",
          message: "新一轮写入",
          metadata: { toolName: "write_note", toolInput: '{"file":"b.md"}' },
        },
      ],
      resolve,
    };
    view.rerender(
      <RuntimeProvider>
        <ThreadArea llmReady={null} />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByRole("button", { name: "批准" })).toBeTruthy());
    expect(screen.queryByText(/等待其余确认/)).toBeNull();
    expect(screen.getByText("新一轮写入")).toBeTruthy();
    // 新卡点击照常 resolve
    fireEvent.click(screen.getByRole("button", { name: "批准" }));
    expect(resolve).toHaveBeenCalledWith({ approved: true }, "reply-9:call_x");
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

  // ———— MS-29 F1：信任事件订阅接线（与 FR-8 同一 subscribe 调用，按 name 分派）————

  function lastTrustSubscriber() {
    const calls = mocks.agent.subscribe.mock.calls;
    expect(calls.length).toBeGreaterThan(0);
    return calls[calls.length - 1][0] as {
      onCustomEvent: (params: {
        event: { name?: unknown; value?: unknown };
        messages: Message[];
      }) => { messages: Message[] } | undefined;
    };
  }

  it("trust.anchors 事件 → 全局 trustStore 落地（F2 渲染数据源）", async () => {
    renderThread();
    await waitFor(() => expect(mocks.agent.subscribe).toHaveBeenCalled());
    lastTrustSubscriber().onCustomEvent({
      event: {
        name: TRUST_ANCHORS_EVENT,
        value: {
          messageId: "ta-trust-1",
          payload: {
            v: 1,
            anchors: [{ snippet: "1741 亿", occ: 1, state: "verified" }],
            stats: { verified: 1, sourced: 0, unverified: 0 },
          },
        },
      },
      messages: [],
    });
    expect(trustStore.get("ta-trust-1")?.stats.verified).toBe(1);
  });

  it("trust.correction 事件 → 返回 AgentStateMutation（occ 第 2 次出现原位替换）", async () => {
    renderThread();
    await waitFor(() => expect(mocks.agent.subscribe).toHaveBeenCalled());
    const messages = [
      { id: "ta-u1", role: "user", content: "看看" } as Message,
      { id: "ta-trust-2", role: "assistant", content: "营收 1741 亿（另一处 1741 亿）。" } as Message,
    ];
    const mutation = lastTrustSubscriber().onCustomEvent({
      event: {
        name: TRUST_CORRECTION_EVENT,
        value: { messageId: "ta-trust-2", snippet: "1741 亿", occ: 2, replacement: "1708 亿", note: "n1" },
      },
      messages,
    });
    expect(mutation?.messages?.[1].content).toBe("营收 1741 亿（另一处 1708 亿）。");
    expect(trustStore.get("ta-trust-2")?.correction?.notes).toEqual(["n1"]);
  });

  it("未知 name 的 Custom 事件安全忽略（不写 store、不产 mutation）", async () => {
    renderThread();
    await waitFor(() => expect(mocks.agent.subscribe).toHaveBeenCalled());
    const mutation = lastTrustSubscriber().onCustomEvent({
      event: { name: "other.custom", value: { whatever: 1 } },
      messages: [],
    });
    expect(mutation).toBeUndefined();
    expect(trustStore.get("ta-trust-unknown")).toBeUndefined();
  });

  // ———— MS-29 F2：角标渲染接线（TrustMessageContent 独立订阅 store，AssistantMessage memo 之外）————

  it("F2：anchors 落 store 后角标原位渲染（未落 store 的消息零角标）", async () => {
    mocks.agent.messages = [
      agentMessage({ id: "ta-f2-u", role: "user", content: "看看" }),
      agentMessage({ id: "ta-f2-a1", role: "assistant", content: "现价1520.33元，历史估值约38倍。" }),
    ];
    renderThread();
    await waitFor(() => expect(screen.getByText(/现价/)).toBeTruthy());
    // 落地前：无角标（无 payload 走原 MarkdownView 路径）
    expect(screen.queryAllByTestId("trust-anchor-badge")).toHaveLength(0);
    act(() => {
      lastTrustSubscriber().onCustomEvent({
        event: {
          name: TRUST_ANCHORS_EVENT,
          value: {
            messageId: "ta-f2-a1",
            payload: {
              v: 1,
              anchors: [
                {
                  snippet: "1520.33元",
                  occ: 1,
                  state: "verified",
                  tool: "get_quote",
                  args: { symbol: "600519.SH" },
                  asOf: "2026-10-05 14:59:32",
                  asOfKind: "data",
                  raw: "1520.33",
                },
                { snippet: "38倍", occ: 1, state: "unverified" },
              ],
              stats: { verified: 1, sourced: 0, unverified: 1 },
            },
          },
        },
        messages: [],
      });
    });
    const badges = await screen.findAllByTestId("trust-anchor-badge");
    expect(badges).toHaveLength(2);
    expect(badges.map((b) => b.getAttribute("data-anchor-state"))).toEqual(["verified", "unverified"]);
    // 浮层常驻 DOM（group-hover 显隐）：verified 措辞 + 来源字段
    expect(screen.getByText("数值与工具返回一致")).toBeTruthy();
    expect(screen.getByText("数据时间戳：2026-10-05 14:59:32")).toBeTruthy();
  });

  it("F2：correction 改写后到达的 anchors（snippet 为替换形态）对当前文本直接定位", async () => {
    // F1 替换产物（occ=2 的 1708 亿）已写回 content，B5 终态锚定 snippet 即 replacement 形态
    mocks.agent.messages = [
      agentMessage({ id: "ta-f2-a2", role: "assistant", content: "A 1741 亿 B 1708 亿 C。" }),
    ];
    renderThread();
    await waitFor(() => expect(screen.getByText(/1741/)).toBeTruthy());
    act(() => {
      lastTrustSubscriber().onCustomEvent({
        event: {
          name: TRUST_ANCHORS_EVENT,
          value: {
            messageId: "ta-f2-a2",
            payload: {
              v: 1,
              anchors: [{ snippet: "1708 亿", occ: 1, state: "verified" }],
              stats: { verified: 1, sourced: 0, unverified: 0 },
            },
          },
        },
        messages: [],
      });
    });
    const badges = await screen.findAllByTestId("trust-anchor-badge");
    expect(badges).toHaveLength(1);
    // 角标落在替换后的 1708 亿上（包裹元素含 snippet 原文）
    expect(badges[0].parentElement?.textContent).toContain("1708 亿");
  });

  // ———— F6 修复轮：修正注记 live 可见（拍板 #10 注记保留——correction 事件落 store 即渲染）————

  it("F6：correction 事件落地 → 注记引用块 live 出现（anchors 未到也渲染，正文与横幅之间）", async () => {
    mocks.agent.messages = [
      agentMessage({ id: "ta-f6-u", role: "user", content: "看看" }),
      agentMessage({ id: "ta-f6-a1", role: "assistant", content: "现价1520.33元。" }),
    ];
    renderThread();
    await waitFor(() => expect(screen.getByText(/现价/)).toBeTruthy());
    // 事件落地前：无注记块
    expect(screen.queryByTestId("trust-correction-notes")).toBeNull();
    act(() => {
      lastTrustSubscriber().onCustomEvent({
        event: {
          name: TRUST_CORRECTION_EVENT,
          value: {
            messageId: "ta-f6-a1",
            snippet: "15.20元",
            occ: 1,
            replacement: "1520.33元",
            note: "原文误述 15.20元",
          },
        },
        messages: mocks.agent.messages,
      });
    });
    // 占位 payload（anchors 未到）即携带 correction.notes → 注记块 live 出现
    const block = await screen.findByTestId("trust-correction-notes");
    expect(block.textContent).toContain("校验修正：原文误述 15.20元");
    // 注记块在正文之后（内容尾部挂点）
    const content = screen.getByText(/现价/);
    expect(
      (content.compareDocumentPosition(block) & Node.DOCUMENT_POSITION_FOLLOWING) !== 0,
    ).toBe(true);
  });

  // ———— MS-29 F3：低置信横幅 + disclaimer 接线（TrustMessageAdvisories 同消息级订阅，正文与反馈条之间）————

  it("F3：payload 携带 confidence/advice → 横幅与 disclaimer 落在正文与反馈条之间（并存不互斥）", async () => {
    mocks.agent.messages = [
      agentMessage({ id: "ta-f3-u", role: "user", content: "看看" }),
      agentMessage({
        id: "ta-f3-a1",
        role: "assistant",
        content: "现价1520.33元，压力1800元，支撑1400元，振幅约8%，可考虑加仓。",
      }),
    ];
    renderThread();
    await waitFor(() => expect(screen.getByText(/现价/)).toBeTruthy());
    // 落地前：零渲染（无 confidence/advice 键）
    expect(screen.queryByTestId("confidence-banner")).toBeNull();
    expect(screen.queryByTestId("disclaimer-note")).toBeNull();
    act(() => {
      lastTrustSubscriber().onCustomEvent({
        event: {
          name: TRUST_ANCHORS_EVENT,
          value: {
            messageId: "ta-f3-a1",
            payload: {
              v: 1,
              anchors: [
                {
                  snippet: "1520.33元",
                  occ: 1,
                  state: "verified",
                  tool: "get_quote",
                  args: { symbol: "600519.SH" },
                  asOf: "2026-10-05 14:59:32",
                  asOfKind: "data",
                  raw: "1520.33",
                },
                { snippet: "1800元", occ: 1, state: "unverified" },
                { snippet: "1400元", occ: 1, state: "unverified" },
                { snippet: "8%", occ: 1, state: "unverified" },
              ],
              stats: { verified: 1, sourced: 0, unverified: 3 },
              advice: {
                flag: true,
                by: "lexicon",
                text: "以上内容由 AI 生成，仅供参考，不构成任何投资建议。",
              },
              confidence: { signals: ["unverified_ratio:0.75", "tool_failures:1"] },
            },
          },
        },
        messages: [],
      });
    });
    const banner = await screen.findByTestId("confidence-banner");
    const note = screen.getByTestId("disclaimer-note");
    expect(screen.getAllByTestId("confidence-signal").map((li) => li.textContent)).toEqual([
      "3 处数字未溯源",
      "工具调用失败",
    ]);
    expect(screen.getByTestId("confidence-suggestion").textContent).toBe(
      "建议重问最新价或查看东方财富行情页",
    );
    expect(screen.getByText("以上内容由 AI 生成，仅供参考，不构成任何投资建议。")).toBeTruthy();
    // 落位链：正文段落 → 横幅 → disclaimer → 反馈条（设计规格 §5.3：插 MarkdownView 与 FeedbackBar 之间）
    const para = screen.getByText(/现价/);
    const up = screen.getByRole("button", { name: "回答有帮助" });
    expect(para.compareDocumentPosition(banner) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(banner.compareDocumentPosition(note) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(note.compareDocumentPosition(up) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("F3：payload 无 confidence/advice 键 → 横幅与 disclaimer 零渲染（角标对照组正常）", async () => {
    mocks.agent.messages = [
      agentMessage({ id: "ta-f3-a2", role: "assistant", content: "现价1520.33元。" }),
    ];
    renderThread();
    await waitFor(() => expect(screen.getByText(/现价/)).toBeTruthy());
    act(() => {
      lastTrustSubscriber().onCustomEvent({
        event: {
          name: TRUST_ANCHORS_EVENT,
          value: {
            messageId: "ta-f3-a2",
            payload: {
              v: 1,
              anchors: [{ snippet: "1520.33元", occ: 1, state: "verified" }],
              stats: { verified: 1, sourced: 0, unverified: 0 },
            },
          },
        },
        messages: [],
      });
    });
    // anchors 正常落地（对照组：同 payload 无 confidence/advice 不影响角标）
    expect(await screen.findAllByTestId("trust-anchor-badge")).toHaveLength(1);
    expect(screen.queryByTestId("confidence-banner")).toBeNull();
    expect(screen.queryByTestId("disclaimer-note")).toBeNull();
  });

  // ———— MS-29 F5：信任锚定回灌重建（回灌 effect 内 GET payload → trustStore.rebuild）————

  /** F5 接线夹具：带 payload 的 assistant 历史（形态 = F4 携带侧 JSON.stringify 产物）。 */
  function f5Payload(verified: number): string {
    return JSON.stringify({
      v: 1,
      anchors: [
        {
          snippet: "1520.33元",
          occ: 1,
          state: "verified",
          tool: "get_quote",
          asOf: "2026-10-05 14:59:32",
          asOfKind: "data",
          raw: "1520.33",
        },
      ],
      stats: { verified, sourced: 0, unverified: 0 },
    });
  }

  it("F5：回灌带 payload 历史 → trustStore 重建 → 角标原位渲染（useTrustPayload 读到回灌数据）", async () => {
    api = installConversationsApi({
      list: [{ id: "t5", title: "信任会话", updatedAt: 2 }],
      messages: {
        t5: [
          { id: "f5-u1", role: "user", content: "看看", createdAt: 1 },
          { id: "f5-a1", role: "assistant", content: "现价1520.33元。", createdAt: 2, payload: f5Payload(1) },
        ],
      },
    });
    // setMessages 是 mock（不回写 mocks.agent.messages）：预置同形消息模拟回灌落地后的渲染态
    mocks.agent.messages = [agentMessage({ id: "f5-a1", role: "assistant", content: "现价1520.33元。" })];
    renderThread();
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    // 回灌 rebuild 落地 → F2 渲染链（useTrustPayload）读到 store 数据 → 角标出现
    const badges = await screen.findAllByTestId("trust-anchor-badge");
    expect(badges).toHaveLength(1);
    expect(badges[0].getAttribute("data-anchor-state")).toBe("verified");
    expect(badges[0].parentElement?.textContent).toContain("1520.33元");
    expect(trustStore.get("f5-a1")?.stats.verified).toBe(1);
  });

  it("F5：切会话 → 空历史 rebuild 全清（store 不残留上一会话数据，角标随 store 清空消失不报错）", async () => {
    api = installConversationsApi({
      list: [
        { id: "t5a", title: "会话一", updatedAt: 2 },
        { id: "t5b", title: "会话二", updatedAt: 1 },
      ],
      messages: {
        t5a: [{ id: "f5-sw-a1", role: "assistant", content: "现价1520.33元。", createdAt: 1, payload: f5Payload(1) }],
        t5b: [],
      },
    });
    mocks.agent.messages = [agentMessage({ id: "f5-sw-a1", role: "assistant", content: "现价1520.33元。" })];
    render(
      <RuntimeProvider>
        <ThreadSwitchHarness targetId="t5b" />
      </RuntimeProvider>,
    );
    // t5a 回灌完成：store 有数据、角标渲染
    await waitFor(() => expect(trustStore.get("f5-sw-a1")?.stats.verified).toBe(1));
    expect(await screen.findAllByTestId("trust-anchor-badge")).toHaveLength(1);
    // 切到 t5b（空历史）→ rebuild([]) 全清
    fireEvent.click(screen.getByText("切到 t5b"));
    await waitFor(() => expect(trustStore.get("f5-sw-a1")).toBeUndefined());
    expect(trustStore.snapshot().size).toBe(0);
    // store 无记录 → 角标零渲染零报错（mock setMessages 不回写，消息文本仍在——
    // 即「消息在而 payload 无」的降级形态对照组）
    expect(screen.queryAllByTestId("trust-anchor-badge")).toHaveLength(0);
  });

  it("F5：回灌历史含解析失败 payload → 该行跳过不炸整批（合法行照常重建，无回灌错误卡）", async () => {
    api = installConversationsApi({
      list: [{ id: "t5c", title: "混合会话", updatedAt: 2 }],
      messages: {
        t5c: [
          { id: "f5-bad-json", role: "assistant", content: "坏JSON", createdAt: 1, payload: "{broken" },
          {
            id: "f5-bad-v",
            role: "assistant",
            content: "坏版本",
            createdAt: 2,
            payload: JSON.stringify({ v: 2, anchors: [], stats: { verified: 0, sourced: 0, unverified: 0 } }),
          },
          { id: "f5-good", role: "assistant", content: "好的", createdAt: 3, payload: f5Payload(1) },
        ],
      },
    });
    renderThread();
    await waitFor(() => expect(mocks.agent.setMessages).toHaveBeenCalled());
    expect(trustStore.get("f5-good")?.stats.verified).toBe(1);
    expect(trustStore.get("f5-bad-json")).toBeUndefined();
    expect(trustStore.get("f5-bad-v")).toBeUndefined();
    // 不炸整批：回灌成功落地（无 hydrate 错误卡）
    expect(screen.queryByTestId("hydrate-error")).toBeNull();
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

    it("IME 组合中 Enter 不发送也不阻止默认行为（isComposing 守卫）", async () => {
      renderThread();
      await waitFor(() => expect(screen.getByPlaceholderText(composerPlaceholder)).toBeTruthy());
      const ta = screen.getByPlaceholderText(composerPlaceholder);
      fireEvent.change(ta, { target: { value: "拼音候选中" } });
      // IME 组合态的 Enter 用于选定候选词：不得提交、不得 preventDefault（否则候选词上屏被吞）
      const composingNotPrevented = fireEvent.keyDown(ta, {
        key: "Enter",
        isComposing: true,
      });
      expect(composingNotPrevented).toBe(true);
      expect(mocks.agent.addMessage).not.toHaveBeenCalled();
      expect(mocks.runAgent).not.toHaveBeenCalled();
      // 组合结束后正常 Enter 恢复发送
      fireEvent.keyDown(ta, { key: "Enter", isComposing: false });
      expect(mocks.agent.addMessage).toHaveBeenCalledWith({
        id: expect.any(String),
        role: "user",
        content: "拼音候选中",
      });
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

  describe("toolMessage 配对（FR-7）", () => {
    const toolCall = {
      id: "tc1",
      type: "function" as const,
      function: { name: "get_kline", arguments: "{}" },
    };

    /** 与既有用例同一渲染入口：先铺 agent.messages 再走 renderThread（RuntimeProvider 异步 ready 后才挂 ThreadArea）。 */
    function renderThreadWithMessages(messages: Message[]) {
      mocks.agent.messages = messages;
      return renderThread();
    }

    it("把 role:tool 消息按 toolCallId 配对传给 renderToolCall", async () => {
      renderThreadWithMessages([
        agentMessage({ id: "a1", role: "assistant", content: "查一下", toolCalls: [toolCall] }),
        agentMessage({ id: "tr1", role: "tool", toolCallId: "tc1", content: '{"specVersion":1}' }),
      ]);
      await waitFor(() => expect(screen.getByTestId("tool-rendered")).toBeTruthy());
      expect(mocks.renderToolCall).toHaveBeenCalledWith(
        expect.objectContaining({
          toolCall: expect.objectContaining({ id: "tc1" }),
          toolMessage: expect.objectContaining({ id: "tr1", toolCallId: "tc1" }),
        }),
      );
    });

    it("无 tool 消息时 toolMessage 为 undefined（不崩）", async () => {
      renderThreadWithMessages([
        agentMessage({ id: "a1", role: "assistant", content: "hi", toolCalls: [toolCall] }),
      ]);
      await waitFor(() => expect(screen.getByTestId("tool-rendered")).toBeTruthy());
      expect(mocks.renderToolCall).toHaveBeenCalledWith(
        expect.objectContaining({ toolMessage: undefined }),
      );
    });

    it("消息数组变化但本条 toolCall 的 toolMessage 未变时不重渲染（memo 比较器）", async () => {
      const assistant = agentMessage({
        id: "a1",
        role: "assistant",
        content: "查一下",
        toolCalls: [toolCall],
      });
      const tool = agentMessage({ id: "tr1", role: "tool", toolCallId: "tc1", content: "{}" });
      const view = renderThreadWithMessages([assistant, tool]);
      await waitFor(() => expect(screen.getByTestId("tool-rendered")).toBeTruthy());
      expect(mocks.renderToolCall).toHaveBeenCalledTimes(1);
      // 追加无关 user 消息 → 新数组、新 Map，但 a1 引用与 tc1 的 toolMessage 引用不变 → 不重渲染
      mocks.agent.messages = [
        assistant,
        tool,
        agentMessage({ id: "u2", role: "user", content: "再问一句" }),
      ];
      view.rerender(
        <RuntimeProvider>
          <ThreadArea llmReady={null} />
        </RuntimeProvider>,
      );
      expect(mocks.renderToolCall).toHaveBeenCalledTimes(1);
      // tool 消息内容更新（新引用；直接构造而非联合类型 spread，避免 content 类型冲突）→ 重渲染
      mocks.agent.messages = [
        assistant,
        agentMessage({
          id: "tr1",
          role: "tool",
          toolCallId: "tc1",
          content: '{"specVersion":1,"type":"candlestick"}',
        }),
      ];
      view.rerender(
        <RuntimeProvider>
          <ThreadArea llmReady={null} />
        </RuntimeProvider>,
      );
      expect(mocks.renderToolCall).toHaveBeenCalledTimes(2);
    });
  });
});

describe("markdown 图片/链接防御（FR-6）", () => {
  const md = [
    "![快照](https://cdn.example.com/snapshot.png)",
    "![明文](http://insecure.example.com/x.png)",
    "[资料](https://example.com/doc)",
  ].join("\n");

  /** 与既有用例同一渲染入口：铺 agent.messages 后走 renderThread，等链接文本可见即 Markdown 已渲染。 */
  async function renderMarkdownMessage() {
    mocks.agent.messages = [agentMessage({ id: "a1", role: "assistant", content: md })];
    renderThread();
    await waitFor(() => expect(screen.getByText("资料")).toBeTruthy());
  }

  it("https 图片：懒加载 + no-referrer + 尺寸约束", async () => {
    await renderMarkdownMessage();
    const img = document.querySelector('img[src*="cdn.example.com"]') as HTMLImageElement;
    expect(img.getAttribute("loading")).toBe("lazy");
    expect(img.getAttribute("decoding")).toBe("async");
    expect(img.getAttribute("referrerPolicy")).toBe("no-referrer");
    expect(img.className).toContain("max-w-full");
    expect(img.getAttribute("alt")).toBe("快照");
  });

  it("http 图片被 urlTransform 拦截（不渲染 img）", async () => {
    await renderMarkdownMessage();
    expect(document.querySelector('img[src*="insecure.example.com"]')).toBeNull();
  });

  it("链接新窗口打开 + noopener", async () => {
    await renderMarkdownMessage();
    const a = document.querySelector('a[href="https://example.com/doc"]') as HTMLAnchorElement;
    expect(a.getAttribute("target")).toBe("_blank");
    expect(a.getAttribute("rel")).toBe("noopener noreferrer");
  });
});
