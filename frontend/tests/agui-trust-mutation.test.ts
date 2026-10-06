import { describe, expect, it, vi } from "vitest";
import { HttpAgent } from "@ag-ui/client";
import {
  TRUST_ANCHORS_EVENT,
  TRUST_CORRECTION_EVENT,
  handleTrustCustomEvent,
  trustStore,
} from "@/lib/trustMeta";

// ———— MS-29 F1：AgentStateMutation 运行验证（B0 探针 3 前端半）————
// 走**真实** @ag-ui/client 0.0.59 全管线（HttpAgent + 假 SSE，与 tests/agui-stream.test.ts 同法）：
// parseSSEStream → verifyEvents → transformChunks → defaultApplyEvents（subscriber 返回的
// AgentStateMutation 在此合并）→ processApplyEvents（写回 agent.messages）。
// 断言：ThreadArea 同款 onCustomEvent 订阅返回 {messages} 后，库管线确实把替换结果写进
// agent.messages——sanctioned 原位替换路径成立，无需 agent.setMessages 回退。
// （vitest 环境下 VITEST_WORKER_ID 置位 → 管线深冻结 params.messages 且原位改写抛 TypeError，
// 本用例同时证明 handler 不改冻结入参。真浏览器 e2e 归 F6。）

/** 构造最小 SSE 响应（text/event-stream，AG-UI 事件 JSON 放 data 行）。 */
function sseResponse(events: Record<string, unknown>[]): Response {
  const body = events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join("");
  return new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

function makeAgent(events: Record<string, unknown>[]) {
  const fetchMock = vi.fn(async () => sseResponse(events));
  const agent = new HttpAgent({
    url: "http://test.local/agui/run",
    fetch: fetchMock as unknown as HttpAgent["fetch"],
  });
  return { agent, fetchMock };
}

const RUN_STARTED = { type: "RUN_STARTED", threadId: "t-trust", runId: "r1" };
const RUN_FINISHED = { type: "RUN_FINISHED", threadId: "t-trust", runId: "r1" };

/** 一轮带信任事件的完整消息流：assistant 文本含两处「1741 亿」，correction 先、anchors 后。 */
function trustStream(messageId: string, extraEvents: Record<string, unknown>[] = []) {
  return [
    RUN_STARTED,
    { type: "TEXT_MESSAGE_START", messageId, role: "assistant" },
    { type: "TEXT_MESSAGE_CONTENT", messageId, delta: "2024 年营收 1741 亿（环比口径 1741 亿）。" },
    { type: "TEXT_MESSAGE_END", messageId },
    {
      type: "CUSTOM",
      name: TRUST_CORRECTION_EVENT,
      value: { messageId, snippet: "1741 亿", occ: 2, replacement: "1708 亿", note: "环比口径已按工具返回修正" },
    },
    ...extraEvents,
    {
      type: "CUSTOM",
      name: TRUST_ANCHORS_EVENT,
      value: {
        messageId,
        payload: {
          v: 1,
          anchors: [{ snippet: "1741 亿", occ: 1, state: "verified" }],
          stats: { verified: 1, sourced: 0, unverified: 0 },
        },
      },
    },
    RUN_FINISHED,
  ];
}

/** ThreadArea 同款订阅接线：唯一出口是 handleTrustCustomEvent。 */
function subscribeTrust(agent: HttpAgent) {
  return agent.subscribe({
    onCustomEvent: ({ event, messages }) => handleTrustCustomEvent(event, messages),
  });
}

describe("信任事件 × 真实 @ag-ui/client 管线（AgentStateMutation 原位替换）", () => {
  it("correction 经管线替换 occ 第 2 次出现并写回 agent.messages；anchors 落 store", async () => {
    const { agent } = makeAgent(trustStream("a-trust-1"));
    agent.addMessage({ id: "u-trust-1", role: "user", content: "看看年报" });
    const sub = subscribeTrust(agent);

    await agent.runAgent();

    // 替换写回：第 2 次「1741 亿」→「1708 亿」，第 1 处保持
    const assistant = agent.messages.find((m) => m.id === "a-trust-1");
    expect(assistant?.content).toBe("2024 年营收 1741 亿（环比口径 1708 亿）。");
    // store：anchors payload 落地且并入 correction 注记（correction 事件先到）
    expect(trustStore.get("a-trust-1")?.stats).toEqual({ verified: 1, sourced: 0, unverified: 0 });
    expect(trustStore.get("a-trust-1")?.correction?.notes).toEqual(["环比口径已按工具返回修正"]);
    sub.unsubscribe();
  });

  it("同流内 correction 事件重放：替换目标已不存在 → no-op，不重复替换、注记不重复", async () => {
    const replay = {
      type: "CUSTOM",
      name: TRUST_CORRECTION_EVENT,
      value: { messageId: "a-trust-2", snippet: "1741 亿", occ: 2, replacement: "1708 亿", note: "环比口径已按工具返回修正" },
    };
    const { agent } = makeAgent(trustStream("a-trust-2", [replay]));
    agent.addMessage({ id: "u-trust-2", role: "user", content: "看看年报" });
    const sub = subscribeTrust(agent);

    await agent.runAgent();

    const assistant = agent.messages.find((m) => m.id === "a-trust-2");
    expect(assistant?.content).toBe("2024 年营收 1741 亿（环比口径 1708 亿）。");
    expect(trustStore.get("a-trust-2")?.correction?.notes).toEqual(["环比口径已按工具返回修正"]);
    sub.unsubscribe();
  });

  it("同 snippet 多条修正（F1 修复轮 I-1）：occ 对改写前原文解析，终态与后端 applyReplacements 回写一致", async () => {
    // 后端语义（ConsistencyValidator.applyReplacements）：全部偏差区间在「同一原文」上解析
    // （occ 定义在改写前文本），自右向左回写。原文 3 处「1741 亿」，修正 occ=1→1708 亿、
    // occ=3→1696 亿，期望终态两处替换、中间一处保持。
    const { agent } = makeAgent([
      RUN_STARTED,
      { type: "TEXT_MESSAGE_START", messageId: "a-trust-3", role: "assistant" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "a-trust-3", delta: "A 1741 亿 B 1741 亿 C 1741 亿 D" },
      { type: "TEXT_MESSAGE_END", messageId: "a-trust-3" },
      {
        type: "CUSTOM",
        name: TRUST_CORRECTION_EVENT,
        value: { messageId: "a-trust-3", snippet: "1741 亿", occ: 1, replacement: "1708 亿", note: "第 1 处已按工具返回修正" },
      },
      {
        type: "CUSTOM",
        name: TRUST_CORRECTION_EVENT,
        value: { messageId: "a-trust-3", snippet: "1741 亿", occ: 3, replacement: "1696 亿", note: "第 3 处已按工具返回修正" },
      },
      {
        type: "CUSTOM",
        name: TRUST_ANCHORS_EVENT,
        value: {
          messageId: "a-trust-3",
          payload: {
            v: 1,
            anchors: [{ snippet: "1741 亿", occ: 2, state: "verified" }],
            stats: { verified: 1, sourced: 0, unverified: 0 },
          },
        },
      },
      RUN_FINISHED,
    ]);
    agent.addMessage({ id: "u-trust-3", role: "user", content: "看看年报" });
    const sub = subscribeTrust(agent);

    await agent.runAgent();

    // 旧实现的分歧：第 2 条 occ=3 对「已改写文本」（只剩 2 处）解析 → -1 静默丢弃 → 刷新后
    // 与后端落盘文本跳变。台账语义下两条均对原文解析 → 与后端一致。
    const assistant = agent.messages.find((m) => m.id === "a-trust-3");
    expect(assistant?.content).toBe("A 1708 亿 B 1741 亿 C 1696 亿 D");
    expect(trustStore.get("a-trust-3")?.correction?.notes).toEqual([
      "第 1 处已按工具返回修正",
      "第 3 处已按工具返回修正",
    ]);
    sub.unsubscribe();
  });
});
