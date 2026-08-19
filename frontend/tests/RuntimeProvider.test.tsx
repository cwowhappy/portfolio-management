import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import type { Message } from "@ag-ui/client";
import {
  AGENT_ID,
  agentMessagesToHistory,
  historyToAgentMessages,
  RuntimeProvider,
  useChatRuntime,
} from "@/components/chat/RuntimeProvider";
import type { ChatMessage } from "@/lib/types";

afterEach(cleanup);

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
      <button data-testid="new" onClick={ctx.newThread}>新</button>
      <button data-testid="switch" onClick={() => ctx.switchThread("t2")}>切</button>
      <button data-testid="del" onClick={() => ctx.deleteThread("t1")}>删</button>
      <button
        data-testid="persist"
        onClick={() => ctx.persistMessages("t1", [msg("user", "你好")])}
      >
        存
      </button>
    </div>
  );
}

describe("RuntimeProvider", () => {
  it("就绪前不渲染子内容，挂载后从 localStorage 恢复会话", async () => {
    localStorage.setItem(
      "invest.sessions",
      JSON.stringify([
        { id: "t1", title: "会话一", updatedAt: 200 },
        { id: "t2", title: "会话二", updatedAt: 100 },
      ]),
    );
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    expect(screen.getByTestId("count").textContent).toBe("2");
  });

  it("无历史会话时生成新 threadId", async () => {
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() =>
      expect(screen.getByTestId("thread").textContent).not.toBe(""),
    );
  });

  it("新对话 / 切换会话", async () => {
    localStorage.setItem(
      "invest.sessions",
      JSON.stringify([
        { id: "t1", title: "一", updatedAt: 200 },
        { id: "t2", title: "二", updatedAt: 100 },
      ]),
    );
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    fireEvent.click(screen.getByTestId("switch"));
    expect(screen.getByTestId("thread").textContent).toBe("t2");
    fireEvent.click(screen.getByTestId("new"));
    const id = screen.getByTestId("thread").textContent;
    expect(id).not.toBe("t2");
    expect(id).not.toBe("");
  });

  it("删除当前会话后自动切换到新线程", async () => {
    localStorage.setItem(
      "invest.sessions",
      JSON.stringify([{ id: "t1", title: "一", updatedAt: 200 }]),
    );
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    fireEvent.click(screen.getByTestId("del"));
    await waitFor(() => {
      expect(screen.getByTestId("count").textContent).toBe("0");
      expect(screen.getByTestId("thread").textContent).not.toBe("t1");
    });
  });

  it("删除非当前会话时保持当前线程", async () => {
    localStorage.setItem(
      "invest.sessions",
      JSON.stringify([
        { id: "t1", title: "一", updatedAt: 200 },
        { id: "t2", title: "二", updatedAt: 100 },
      ]),
    );
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    fireEvent.click(screen.getByTestId("switch"));
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t2"));
    fireEvent.click(screen.getByTestId("del")); // 删除 t1（非当前）
    expect(screen.getByTestId("thread").textContent).toBe("t2");
    expect(screen.getByTestId("count").textContent).toBe("1");
  });

  it("持久化消息并刷新会话列表", async () => {
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).not.toBe(""));
    fireEvent.click(screen.getByTestId("persist"));
    await waitFor(() => expect(screen.getByTestId("count").textContent).toBe("1"));
    expect(localStorage.getItem("invest.messages.t1")).toContain("你好");
  });

  it("Provider 外使用 useChatRuntime 抛错", () => {
    expect(() => render(<Probe />)).toThrowError(/RuntimeProvider/);
  });

  it("AGENT_ID 为 invest（与后端 agent id 一致）", () => {
    expect(AGENT_ID).toBe("invest");
  });
});
