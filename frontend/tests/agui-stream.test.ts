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
});
