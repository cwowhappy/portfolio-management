import { describe, expect, it, vi } from "vitest";
import { HttpAgent } from "@ag-ui/client";
import { agentMessagesToHistory } from "@/components/chat/RuntimeProvider";

// ———— 方案取舍 ————
// AG-UI 事件 → agent.messages 的归约发生在 @ag-ui/client（HttpAgent/AbstractAgent）内部，
// RuntimeProvider/route.ts 只是装配层，没有自有的事件解析代码可注入替身。
// 因此这里分两层覆盖：
//   本文件：用注入的 fake fetch 构造假 AG-UI SSE 事件序列，驱动**真实** HttpAgent，
//           验证事件消费（消息组装/工具调用/中断截断）与钉住的客户端版本行为一致；
//           并接到我们自己的 agentMessagesToHistory，断言「写库不丢已收到部分」。
//   ThreadArea.stream.test.tsx：在 mock 边界内验证我们自己的持久化与错误 UI
//           （runAgent 拒绝 → sendError 横幅、isRunning 边沿 flush 部分消息等）。

/** 构造最小 SSE 响应（text/event-stream，AG-UI 事件 JSON 放 data 行）。 */
function sseResponse(events: Record<string, unknown>[], status = 200): Response {
  const body = events.map((e) => `data: ${JSON.stringify(e)}\n\n`).join("");
  return new Response(body, {
    status,
    headers: { "Content-Type": "text/event-stream" },
  });
}

/** 用注入 fetch 的真实 HttpAgent 消费一组假 AG-UI 事件。 */
function makeAgent(events: Record<string, unknown>[], status = 200) {
  const fetchMock = vi.fn(async () => sseResponse(events, status));
  const agent = new HttpAgent({
    url: "http://test.local/agui/run",
    fetch: fetchMock as unknown as HttpAgent["fetch"],
  });
  return { agent, fetchMock };
}

const RUN_STARTED = { type: "RUN_STARTED", threadId: "t1", runId: "r1" };
const RUN_FINISHED = { type: "RUN_FINISHED", threadId: "t1", runId: "r1" };

describe("AG-UI 事件流（真实 HttpAgent + 假 SSE 帧）", () => {
  it("文本流：多段 delta 组装为单条 assistant 消息，运行结束 isRunning 复位", async () => {
    const { agent } = makeAgent([
      RUN_STARTED,
      { type: "TEXT_MESSAGE_START", messageId: "a1", role: "assistant" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "a1", delta: "你好" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "a1", delta: "，世界" },
      { type: "TEXT_MESSAGE_END", messageId: "a1" },
      RUN_FINISHED,
    ]);
    agent.addMessage({ id: "u1", role: "user", content: "问题" });
    const result = await agent.runAgent();
    expect(agent.messages).toEqual([
      { id: "u1", role: "user", content: "问题" },
      { id: "a1", role: "assistant", content: "你好，世界" },
    ]);
    expect(result.newMessages).toEqual([
      { id: "a1", role: "assistant", content: "你好，世界" },
    ]);
    expect(agent.isRunning).toBe(false);
  });

  it("工具调用流：参数分片拼接并挂到父 assistant 消息，工具结果单列一条", async () => {
    const { agent } = makeAgent([
      RUN_STARTED,
      { type: "TEXT_MESSAGE_START", messageId: "a1", role: "assistant" },
      {
        type: "TOOL_CALL_START",
        toolCallId: "tc1",
        toolCallName: "get_quote",
        parentMessageId: "a1",
      },
      { type: "TOOL_CALL_ARGS", toolCallId: "tc1", delta: '{"code":' },
      { type: "TOOL_CALL_ARGS", toolCallId: "tc1", delta: '"600519"}' },
      { type: "TOOL_CALL_END", toolCallId: "tc1" },
      { type: "TOOL_CALL_RESULT", messageId: "tr1", toolCallId: "tc1", content: '{"price":1600}' },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "a1", delta: "茅台现价 1600" },
      { type: "TEXT_MESSAGE_END", messageId: "a1" },
      RUN_FINISHED,
    ]);
    await agent.runAgent();
    expect(agent.messages).toHaveLength(2);
    const assistant = agent.messages[0];
    expect(assistant.role).toBe("assistant");
    if (assistant.role !== "assistant") return;
    expect(assistant.toolCalls).toEqual([
      { id: "tc1", type: "function", function: { name: "get_quote", arguments: '{"code":"600519"}' } },
    ]);
    expect(agent.messages[1]).toMatchObject({ id: "tr1", role: "tool", toolCallId: "tc1" });
  });

  it("持久化口径：工具调用与工具结果不入库，只留纯文本（ADR-0004 精简历史）", async () => {
    const { agent } = makeAgent([
      RUN_STARTED,
      { type: "TEXT_MESSAGE_START", messageId: "a1", role: "assistant" },
      {
        type: "TOOL_CALL_START",
        toolCallId: "tc1",
        toolCallName: "get_quote",
        parentMessageId: "a1",
      },
      { type: "TOOL_CALL_ARGS", toolCallId: "tc1", delta: '{"code":"600519"}' },
      { type: "TOOL_CALL_END", toolCallId: "tc1" },
      { type: "TOOL_CALL_RESULT", messageId: "tr1", toolCallId: "tc1", content: "{}" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "a1", delta: "结论" },
      { type: "TEXT_MESSAGE_END", messageId: "a1" },
      RUN_FINISHED,
    ]);
    agent.addMessage({ id: "u1", role: "user", content: "茅台多少" });
    await agent.runAgent();
    const history = agentMessagesToHistory(agent.messages);
    expect(history.map((m) => [m.role, m.content])).toEqual([
      ["user", "茅台多少"],
      ["assistant", "结论"],
    ]);
  });

  it("RUN_ERROR 中断：runAgent 正常返回，已收到的部分内容保留在消息列表", async () => {
    const { agent } = makeAgent([
      RUN_STARTED,
      { type: "TEXT_MESSAGE_START", messageId: "a1", role: "assistant" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "a1", delta: "部分回答" },
      { type: "RUN_ERROR", message: "上游模型超时", code: "UPSTREAM_TIMEOUT" },
    ]);
    // 探针实测：RUN_ERROR 不 throw（网络层失败才 throw），部分消息保留、isRunning 复位
    await agent.runAgent();
    expect(agent.messages).toEqual([
      { id: "a1", role: "assistant", content: "部分回答" },
    ]);
    expect(agent.isRunning).toBe(false);
    // 中断后持久化不丢已收到部分
    expect(agentMessagesToHistory(agent.messages)).toEqual([
      expect.objectContaining({ id: "a1", role: "assistant", content: "部分回答" }),
    ]);
  });

  it("断流（无 TEXT_MESSAGE_END/RUN_FINISHED 直接关闭）：部分内容保留，isRunning 复位", async () => {
    const { agent } = makeAgent([
      RUN_STARTED,
      { type: "TEXT_MESSAGE_START", messageId: "a1", role: "assistant" },
      { type: "TEXT_MESSAGE_CONTENT", messageId: "a1", delta: "断流前的内容" },
    ]);
    await agent.runAgent();
    expect(agent.messages).toEqual([
      { id: "a1", role: "assistant", content: "断流前的内容" },
    ]);
    expect(agent.isRunning).toBe(false);
  });

  it("HTTP 502（非 SSE 响应）：runAgent 抛错，不写入半截消息", async () => {
    const { agent } = makeAgent([], 502);
    await expect(agent.runAgent()).rejects.toThrow();
    expect(agent.messages).toEqual([]);
    expect(agent.isRunning).toBe(false);
  });

  // ———— HITL interrupt（agentscope 2.0.3 权限确认流，useInterrupt 依赖的客户端层契约） ————

  const INTERRUPT = {
    id: "reply-1:tc9",
    reason: "tool_call",
    toolCallId: "tc9",
    message: "Need approval before running this tool",
    metadata: { "agentscope.interruptKind": "permission_confirm", toolName: "test_write" },
  };

  it("RUN_FINISHED 携带 outcome.interrupt：runAgent 正常结束且 pendingInterrupts 填充", async () => {
    const { agent } = makeAgent([
      RUN_STARTED,
      { type: "TOOL_CALL_START", toolCallId: "tc9", toolCallName: "test_write", parentMessageId: "a1" },
      { type: "TOOL_CALL_ARGS", toolCallId: "tc9", delta: '{"note":"x"}' },
      { type: "TOOL_CALL_END", toolCallId: "tc9" },
      {
        type: "RUN_FINISHED",
        threadId: "t1",
        runId: "r1",
        outcome: { type: "interrupt", interrupts: [INTERRUPT] },
      },
    ]);
    agent.addMessage({ id: "u1", role: "user", content: "写一条记录" });
    // 中断即正常结束：runAgent resolve、不 throw（钉住 0.0.59 行为，useInterrupt 的前提）
    await agent.runAgent();
    expect(agent.isRunning).toBe(false);
    expect(agent.pendingInterrupts).toEqual([INTERRUPT]);
  });

  it("resolve 后续跑：runAgent({resume}) 把 ResumeEntry 原样放进请求体", async () => {
    const events = [
      RUN_STARTED,
      {
        type: "RUN_FINISHED",
        threadId: "t1",
        runId: "r1",
        outcome: { type: "interrupt", interrupts: [INTERRUPT] },
      },
    ];
    const resumeEvents = [RUN_STARTED, RUN_FINISHED];
    const bodies: unknown[] = [];
    const fetchMock = vi.fn(async (_url: string, init: RequestInit) => {
      bodies.push(JSON.parse(String(init.body)));
      // 第一轮返回中断流，第二轮返回普通完成流
      return sseResponse(bodies.length === 1 ? events : resumeEvents);
    });
    const agent = new HttpAgent({ url: "http://test.local/agui/run", fetch: fetchMock as unknown as HttpAgent["fetch"] });
    agent.addMessage({ id: "u1", role: "user", content: "写一条记录" });
    await agent.runAgent();
    expect(agent.pendingInterrupts).toHaveLength(1);

    await agent.runAgent({
      resume: [{ interruptId: INTERRUPT.id, status: "resolved", payload: { approved: true } }],
    });

    // 第二次请求体：resume 数组原样出现在 RunAgentInput 顶层（后端 AguiResumeCoordinator 的入口）
    expect(bodies).toHaveLength(2);
    expect(bodies[1]).toMatchObject({
      resume: [{ interruptId: "reply-1:tc9", status: "resolved", payload: { approved: true } }],
    });
    // 成功续跑后 pendingInterrupts 清空（客户端 0.0.59 语义）
    expect(agent.pendingInterrupts).toEqual([]);
  });
});
