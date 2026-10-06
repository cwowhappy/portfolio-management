import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Message } from "@ag-ui/client";
import {
  AGENT_ID,
  agentMessagesToHistory,
  historyToAgentMessages,
  rebuildTrustFromHistory,
  RuntimeProvider,
  useChatRuntime,
} from "@/components/chat/RuntimeProvider";
import {
  createTrustStore,
  handleTrustCustomEvent,
  trustStore,
  TRUST_ANCHORS_EVENT,
  TRUST_CORRECTION_EVENT,
  type TrustPayload,
} from "@/lib/trustMeta";
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

// B6 并发写（多标签页）：union 语义——输出 = 服务端 existing ∪ 本地 msgs，
// 防止本窗口 PUT 整体覆盖时把其他窗口刚落库的消息抹掉（互删）。
describe("agentMessagesToHistory union（并发写防互删）", () => {
  const serverMsg = (id: string, createdAt: number): ChatMessage => ({
    id,
    role: "user",
    content: `服务端${id}`,
    createdAt,
  });

  it("服务端 {1,2} + 本地 {2,3} → {1,2,3}（服务端在前，按 id 去重）", () => {
    const local: Message[] = [
      { id: "2", role: "user", content: "本地2" },
      { id: "3", role: "assistant", content: "本地3" },
    ];
    const out = agentMessagesToHistory(local, [serverMsg("1", 100), serverMsg("2", 200)]);
    expect(out.map((m) => m.id)).toEqual(["1", "2", "3"]);
  });

  it("共有消息保留服务端原文与 createdAt（本地快照不作覆盖）", () => {
    const local: Message[] = [{ id: "2", role: "user", content: "本地旧副本" }];
    const out = agentMessagesToHistory(local, [serverMsg("2", 200)]);
    expect(out).toEqual([{ id: "2", role: "user", content: "服务端2", createdAt: 200 }]);
  });

  it("本地新消息在 existing 中没有 createdAt 时取 Date.now()", () => {
    vi.useFakeTimers();
    vi.setSystemTime(1_700_000_000_000);
    try {
      const out = agentMessagesToHistory([{ id: "n1", role: "user", content: "新消息" }], []);
      expect(out).toEqual([
        { id: "n1", role: "user", content: "新消息", createdAt: 1_700_000_000_000 },
      ]);
    } finally {
      vi.useRealTimers();
    }
  });

  it("空集边界：双空 → 空；仅服务端 → 原样保留；本地全被过滤时服务端仍在", () => {
    expect(agentMessagesToHistory([], [])).toEqual([]);
    const serverOnly = [serverMsg("1", 100)];
    expect(agentMessagesToHistory([], serverOnly)).toEqual(serverOnly);
    const out = agentMessagesToHistory(
      [{ id: "r1", role: "reasoning", content: "思考" }],
      serverOnly,
    );
    expect(out).toEqual(serverOnly);
  });
});

// MS-29 F4：信任锚定随会话持久化——assistant 消息自 trustStore 按 id 取 payload（JSON 文本）
// 携带进 ChatMessage，user 恒不带；union 同 id 冲突时 payload 非空优先、双侧非空取本地。
describe("agentMessagesToHistory 信任 payload 携带（F4）", () => {
  /** 最小合法 payload v1（占位形态即合法 TrustPayload）。 */
  function tp(verified = 1): TrustPayload {
    return { v: 1, anchors: [], stats: { verified, sourced: 0, unverified: 0 } };
  }

  /** 按表建独立 store（不碰单例，测试间零污染）。 */
  function storeWith(entries: Record<string, TrustPayload>) {
    const store = createTrustStore();
    for (const [id, p] of Object.entries(entries)) store.applyAnchors(id, p);
    return store;
  }

  it("assistant 消息携带 payload（JSON 文本）；user 消息不带（键不存在）", () => {
    const out = agentMessagesToHistory(
      [
        { id: "u1", role: "user", content: "问" },
        { id: "a1", role: "assistant", content: "答" },
      ],
      [],
      storeWith({ a1: tp() }),
    );
    expect(out[0].payload).toBeUndefined();
    expect(Object.prototype.hasOwnProperty.call(out[0], "payload")).toBe(false);
    // 序列化产物是合法 JSON 文本，语义等价 store 内 payload（jsonb 回灌语义等价口径）
    expect(JSON.parse(out[1].payload!)).toEqual(tp());
  });

  describe("union 三态（同 id 冲突时 payload 取非空侧；双空→空）", () => {
    const localMsg: Message[] = [{ id: "a1", role: "assistant", content: "本地答" }];
    const serverMsgWith = (payload?: string): ChatMessage[] => [
      { id: "a1", role: "assistant", content: "服务端答", createdAt: 1, ...(payload !== undefined ? { payload } : {}) },
    ];

    it("双空：两侧都无 payload → 不带", () => {
      const out = agentMessagesToHistory(localMsg, serverMsgWith(), storeWith({}));
      expect(out[0].payload).toBeUndefined();
    });

    it("本地非空 / 远端空：取本地（覆盖携带）；content 仍保留服务端原文", () => {
      const out = agentMessagesToHistory(localMsg, serverMsgWith(), storeWith({ a1: tp() }));
      expect(out[0].payload).toBe(JSON.stringify(tp()));
      expect(out[0].content).toBe("服务端答");
      expect(out[0].createdAt).toBe(1);
    });

    it("远端非空 / 本地空：保留 GET 侧 payload（回灌消息重 PUT 场景）", () => {
      const remote = JSON.stringify(tp(7));
      const out = agentMessagesToHistory(localMsg, serverMsgWith(remote), storeWith({}));
      expect(out[0].payload).toBe(remote);
    });

    it("双侧非空：取本地（最新回合事件先于远端 GET 快照，本地即最新）", () => {
      const out = agentMessagesToHistory(
        localMsg,
        serverMsgWith(JSON.stringify(tp(7))),
        storeWith({ a1: tp(9) }),
      );
      expect(JSON.parse(out[0].payload!)).toEqual(tp(9));
    });
  });

  it("correction 后 content 与 payload 一致携带：替换后的文本 + 后端终态 anchors", () => {
    const store = createTrustStore();
    let messages: Message[] = [
      { id: "u1", role: "user", content: "茅台市值多少" },
      { id: "a1", role: "assistant", content: "茅台市值约 1741 亿。" },
    ];
    // correction 先到：原位替换写回 messages（sanctioned AgentStateMutation 路径）
    const mutation = handleTrustCustomEvent(
      {
        name: TRUST_CORRECTION_EVENT,
        value: { messageId: "a1", snippet: "1741 亿", occ: 1, replacement: "1708 亿", note: "已按工具返回值修正" },
      },
      messages,
      store,
    );
    messages = mutation?.messages ?? messages;
    // anchors 后到：后端终态锚定（锚 snippet 对替换后文本）
    const terminal = {
      v: 1,
      anchors: [{ snippet: "1708 亿", occ: 1, state: "verified", tool: "fetchStockQuote", asOf: "2026-10-01", asOfKind: "data" }],
      stats: { verified: 1, sourced: 0, unverified: 0 },
      correction: { notes: ["已按工具返回值修正"] },
    } as unknown as TrustPayload;
    handleTrustCustomEvent(
      { name: TRUST_ANCHORS_EVENT, value: { messageId: "a1", payload: terminal } },
      messages,
      store,
    );
    const out = agentMessagesToHistory(messages, [], store);
    const a = out.find((m) => m.id === "a1")!;
    expect(a.content).toContain("1708 亿");
    expect(a.content).not.toContain("1741 亿");
    // 携带的 payload 与替换后文本一致：锚 snippet 命中替换后数字、注记并入
    const carried = JSON.parse(a.payload!) as TrustPayload;
    expect(carried.anchors[0].snippet).toBe("1708 亿");
    expect(carried.correction?.notes).toContain("已按工具返回值修正");
  });

  it("缺省 trust 参数取页面级单例 trustStore（ThreadArea flushPersist 接线形态）", () => {
    // 单例无法重置：unique id 隔离本用例，不与其他用例互相污染
    trustStore.applyAnchors("f4-singleton-a1", tp());
    const out = agentMessagesToHistory([
      { id: "f4-singleton-a1", role: "assistant", content: "答" },
    ]);
    expect(JSON.parse(out[0].payload!)).toEqual(tp());
  });
});

// MS-29 F5：信任锚定回灌重建——GET 侧 assistant payload（JSON 文本）解析入
// trustStore.rebuild（整表替换），与 F4 携带路径（agentMessagesToHistory）对称的逆路径。
// user payload（null/缺键）忽略；解析失败行（非法 JSON / schema 不符 / v≠1 / 空串）跳过
// 不炸整批；空历史即全清（切会话语义：store 不残留上一会话数据）。
describe("rebuildTrustFromHistory（F5 回灌重建）", () => {
  /** 最小合法 payload v1（占位形态即合法 TrustPayload）。 */
  function tp5(verified = 1): TrustPayload {
    return { v: 1, anchors: [], stats: { verified, sourced: 0, unverified: 0 } };
  }

  /** GET 侧 ChatMessage 夹具：payload 传 undefined 即缺键（旧记录形态）。 */
  function historyMsg(
    role: "user" | "assistant",
    id: string,
    payload?: string | null,
  ): ChatMessage {
    return {
      id,
      role,
      content: `${id}-content`,
      createdAt: 1,
      ...(payload !== undefined ? { payload } : {}),
    };
  }

  it("assistant payload 批量入 store；user payload（null）忽略；上一会话旧数据整表清除", () => {
    const store = createTrustStore();
    store.applyAnchors("stale", tp5()); // 模块级单例跨会话的残留形态
    rebuildTrustFromHistory(
      [
        historyMsg("user", "u1", null), // B8 契约 GET 回带 user 消息 payload:null
        historyMsg("assistant", "a1", JSON.stringify(tp5())),
        historyMsg("assistant", "a2", JSON.stringify(tp5(2))),
      ],
      store,
    );
    expect(store.get("stale")).toBeUndefined();
    expect(store.get("u1")).toBeUndefined();
    expect(store.get("a1")?.stats.verified).toBe(1);
    expect(store.get("a2")?.stats.verified).toBe(2);
  });

  it("解析失败行（非法 JSON / v≠1 / schema 不符 / 空串）跳过该行不炸整批", () => {
    const store = createTrustStore();
    expect(() =>
      rebuildTrustFromHistory(
        [
          historyMsg("assistant", "bad-json", "{broken"),
          historyMsg("assistant", "bad-v", JSON.stringify({ ...tp5(), v: 2 })),
          historyMsg("assistant", "bad-schema", JSON.stringify({ v: 1, anchors: [] })),
          historyMsg("assistant", "bad-empty", ""),
          historyMsg("assistant", "ok", JSON.stringify(tp5())),
        ],
        store,
      ),
    ).not.toThrow();
    expect(store.get("ok")?.stats.verified).toBe(1);
    for (const id of ["bad-json", "bad-v", "bad-schema", "bad-empty"]) {
      expect(store.get(id)).toBeUndefined();
    }
  });

  it("空历史（切会话 / 新会话）→ rebuild 空表全清（store 不残留上一会话数据）", () => {
    const store = createTrustStore();
    store.applyAnchors("prev", tp5());
    rebuildTrustFromHistory([], store);
    expect(store.snapshot().size).toBe(0);
  });

  it("旧消息无 payload（缺键）→ store 无记录（角标自然降级不报错的前提）", () => {
    const store = createTrustStore();
    rebuildTrustFromHistory([historyMsg("assistant", "legacy")], store);
    expect(store.get("legacy")).toBeUndefined();
    expect(store.snapshot().size).toBe(0);
  });

  it("缺省 trust 参数取页面级单例 trustStore（ThreadArea 回灌接线形态）", () => {
    // rebuild 整表替换自含：本用例自身写入即断言，不依赖其他用例的既有键
    rebuildTrustFromHistory([historyMsg("assistant", "f5-singleton-a1", JSON.stringify(tp5(3)))]);
    expect(trustStore.get("f5-singleton-a1")?.stats.verified).toBe(3);
  });
});

// ———— Provider ————

function Probe() {
  const ctx = useChatRuntime();
  return (
    <div>
      <span data-testid="thread">{ctx.currentThreadId}</span>
      <span data-testid="count">{ctx.sessions.length}</span>
      <span data-testid="sessions">{ctx.sessions.map((s) => `${s.id}:${s.title}`).join("|")}</span>
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
      <button
        data-testid="persist2"
        onClick={() => void ctx.persistMessages("t1", [msg("user", "你好"), msg("assistant", "答复", "m2")])}
      >
        再存
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

  it("乐观更新：新会话首次保存后 GET 列表一次，后续保存不再 GET", async () => {
    const api = installConversationsApi({
      list: [{ id: "t1", title: "新会话", updatedAt: 100 }],
    });
    const getListCount = () =>
      api.fetchMock.mock.calls.filter(
        ([input, init]) =>
          String(input) === "/api/conversations" && (init?.method ?? "GET") === "GET",
      ).length;
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t1"));
    const afterMount = getListCount();

    // 首次保存：本地派生标题（首条用户消息前 24 字），并 refresh 一次同步后端真实状态
    fireEvent.click(screen.getByTestId("persist"));
    await waitFor(() => expect(getListCount()).toBe(afterMount + 1));
    await waitFor(() =>
      expect(screen.getByTestId("sessions").textContent).toBe("t1:你好"),
    );

    // 流式期间的后续保存：只发 PUT，不再 GET 列表
    fireEvent.click(screen.getByTestId("persist2"));
    await waitFor(() =>
      expect(api.state.messages.get("t1")).toContainEqual(
        expect.objectContaining({ id: "m2", content: "答复" }),
      ),
    );
    expect(getListCount()).toBe(afterMount + 1);
  });

  it("乐观更新：保存后按 updatedAt 降序重排会话（与后端列表口径一致）", async () => {
    installConversationsApi({
      list: [
        { id: "t2", title: "较新会话", updatedAt: 200 },
        { id: "t1", title: "旧会话", updatedAt: 100 },
      ],
    });
    render(
      <RuntimeProvider>
        <Probe />
      </RuntimeProvider>,
    );
    await waitFor(() => expect(screen.getByTestId("thread").textContent).toBe("t2"));
    expect(screen.getByTestId("sessions").textContent).toBe("t2:较新会话|t1:旧会话");
    // 保存 t1 后其 updatedAt 最新，本地重排到首位
    fireEvent.click(screen.getByTestId("persist"));
    await waitFor(() =>
      expect(screen.getByTestId("sessions").textContent).toBe("t1:旧会话|t2:较新会话"),
    );
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
