import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Message } from "@ag-ui/client";
import {
  AGENT_ID,
  agentMessagesToHistory,
  historyToAgentMessages,
  RuntimeProvider,
  useChatRuntime,
} from "@/components/chat/RuntimeProvider";
import type { ChatMessage } from "@/lib/types";
import { installConversationsApi } from "@/tests/mockConversationsApi";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

beforeEach(() => {
  localStorage.clear();
});

function msg(role: "user" | "assistant", content: string, id = "m1"): ChatMessage {
  return { id, role, content, createdAt: 1_700_000_000_000 };
}

// ———— 纯函数转换 ————

describe("historyToAgentMessages", () => {
  it("把 user/assistant 历史映射为 AG-UI Message", () => {
    const out = historyToAgentMessages([msg("user", "你好"), msg("assistant", "答复")]);
    expect(out).toEqual([
      { id: "m1", role: "user", content: "你好" },
      { id: "m1", role: "assistant", content: "答复" },
    ]);
  });
});

describe("agentMessagesToHistory", () => {
  it("跳过非 user/assistant 消息", () => {
    const messages: Message[] = [
      { id: "s1", role: "system", content: "系统" },
      { id: "r1", role: "reasoning", content: "思考" },
      { id: "t1", role: "tool", toolCallId: "c1", content: "工具结果" },
      { id: "u1", role: "user", content: " 你好 " },
    ];
    const out = agentMessagesToHistory(messages);
    expect(out).toHaveLength(1);
    expect(out[0]).toMatchObject({ id: "u1", role: "user", content: "你好" });
  });

  it("非字符串或空白内容被跳过", () => {
    const messages: Message[] = [
      { id: "u1", role: "user", content: [{ type: "text", text: "x" }] as unknown as string },
      { id: "u2", role: "user", content: "   " },
      { id: "u3", role: "user", content: " 有效 " },
    ];
    const out = agentMessagesToHistory(messages);
    expect(out.map((m) => m.id)).toEqual(["u3"]);
    expect(out[0].content).toBe("有效");
  });
});

// ———— Provider ————

function Probe() {
  const ctx = useChatRuntime();
  return (
    <div>
      <span data-testid="thread">{ctx.currentThreadId}</span>
      <span data-testid="count">{ctx.sessions.length}</span>
      <button data-testid="new" onClick={() => void ctx.newThread()}>
        新
      </button>
      <button data-testid="switch" onClick={() => ctx.switchThread("t2")}>
        切
      </button>
      <button data-testid="run" onClick={() => ctx.setRunning(true)}>
        运行
      </button>
      <button data-testid="del" onClick={() => void ctx.deleteThread("t1")}>
        删
      </button>
      <button
        data-testid="persist"
        onClick={() => void ctx.persistMessages("t1", [msg("user", "你好")])}
      >
        存
      </button>
    </div>
  );
}

describe("RuntimeProvider", () => {
  it("就绪前不渲染子内容，挂载后从服务端恢复会话", async () => {
    installConversationsApi({
      list: [
        { id: "t1", title: "会话一", updatedAt: 200 },
        { id: "t2", title: "会话二", updatedAt: 100 },
      ],
    });
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    expect(screen.getByTestId("count").textContent).toBe("2");
  });

  it("服务端无会话时创建首个会话", async () => {
    const api = installConversationsApi();
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).not.toBe(""));
    expect(api.state.list).toHaveLength(1);
    expect(api.state.list[0].id).toBe(screen.getByTestId("thread").textContent);
    expect(screen.getByTestId("count").textContent).toBe("1");
  });

  it("新对话 / 切换会话", async () => {
    installConversationsApi({
      list: [
        { id: "t1", title: "一", updatedAt: 200 },
        { id: "t2", title: "二", updatedAt: 100 },
      ],
    });
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    fireEvent.click(screen.getByTestId("switch"));
    expect(screen.getByTestId("thread").textContent).toBe("t2");
    fireEvent.click(screen.getByTestId("new"));
    await waitFor(() => {
      const id = screen.getByTestId("thread").textContent;
      expect(id).not.toBe("t2");
      expect(id).not.toBe("");
    });
  });

  it("删除当前会话后自动创建并切换到新线程", async () => {
    const api = installConversationsApi({
      list: [{ id: "t1", title: "一", updatedAt: 200 }],
    });
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    fireEvent.click(screen.getByTestId("del"));
    await waitFor(() => {
      expect(screen.getByTestId("thread").textContent).not.toBe("t1");
    });
    expect(api.state.list).toHaveLength(1);
    expect(api.state.list[0].id).toBe(screen.getByTestId("thread").textContent);
  });

  it("运行中删除当前会话仍创建替代会话（不悬空）", async () => {
    const api = installConversationsApi({
      list: [{ id: "t1", title: "一", updatedAt: 200 }],
    });
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    fireEvent.click(screen.getByTestId("run")); // running = true
    fireEvent.click(screen.getByTestId("del")); // 删除当前 t1
    await waitFor(() => {
      expect(screen.getByTestId("thread").textContent).not.toBe("t1");
    });
    // 替代会话已创建并成为当前线程，而非悬空
    expect(api.state.list).toHaveLength(1);
    expect(api.state.list[0].id).toBe(screen.getByTestId("thread").textContent);
  });

  it("删除非当前会话时保持当前线程", async () => {
    installConversationsApi({
      list: [
        { id: "t1", title: "一", updatedAt: 200 },
        { id: "t2", title: "二", updatedAt: 100 },
      ],
    });
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    fireEvent.click(screen.getByTestId("switch"));
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t2"));
    fireEvent.click(screen.getByTestId("del")); // 删除 t1（非当前）
    await waitFor(() => expect(screen.getByTestId("count").textContent).toBe("1"));
    expect(screen.getByTestId("thread").textContent).toBe("t2");
  });

  it("持久化消息到服务端并刷新会话列表", async () => {
    const api = installConversationsApi();
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).not.toBe(""));
    fireEvent.click(screen.getByTestId("persist"));
    await waitFor(() => {
      expect(api.state.messages.get("t1")).toContainEqual({
        id: "m1",
        role: "user",
        content: "你好",
        createdAt: 1_700_000_000_000,
      });
    });
    // PUT 后触发 refresh → 重新 GET 会话列表
    expect(api.fetchMock.mock.calls.some(([, init]) => init?.method === "PUT")).toBe(true);
  });

  it("初始化失败时回退为空线程（不白屏）", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new Error("network down");
      }),
    );
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).not.toBe(""));
    expect(screen.getByTestId("count").textContent).toBe("0");
  });

  it("Provider 外使用 useChatRuntime 抛错", () => {
    expect(() => render(<Probe />)).toThrowError(/RuntimeProvider/);
  });

  it("AGENT_ID 为 invest（与后端 agent id 一致）", () => {
    expect(AGENT_ID).toBe("invest");
  });
});
