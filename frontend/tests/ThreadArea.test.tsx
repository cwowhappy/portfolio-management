import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Message } from "@ag-ui/client";
import ThreadArea from "@/components/chat/ThreadArea";
import { RuntimeProvider } from "@/components/chat/RuntimeProvider";
import { installConversationsApi } from "@/tests/mockConversationsApi";

// ———— CopilotKit hooks mock ————

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
  useAgentProps: null as Record<string, unknown> | null,
  defaultToolRender: null as null | ((props: Record<string, unknown>) => React.ReactNode),
  renderToolCall: vi.fn(),
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
  mocks.renderToolCall.mockReset();
  mocks.renderToolCall.mockReturnValue(<div data-testid="tool-rendered" />);
  mocks.defaultToolRender = null;
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
