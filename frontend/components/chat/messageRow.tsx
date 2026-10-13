"use client";
// 消息行（用户/思考/助手 + 思考折叠 + 工具消息索引）：自 ThreadArea 按节拆出
//（2026-10 P3 审查 F4，纯搬移零行为变更）。助手消息的流式 memo 隔离注释随代码保留。

import { memo } from "react";
import type { Message } from "@ag-ui/client";
import { useRenderToolCall } from "@copilotkit/react-core/v2";
import { TrustMessageContent } from "@/components/shared/TrustMarkdownView";
import { TrustMessageAdvisories } from "@/components/shared/TrustAdvisories";
import { FeedbackBar } from "./feedbackBar";

// ———— 思考折叠 ————

function Reasoning({ text }: { text: string }) {
  if (!text) return null;
  return (
    <details className="mb-2 rounded-md border border-[color:var(--color-line-soft)] bg-[color:var(--color-bg-soft)] px-3 py-2">
      <summary className="cursor-pointer select-none text-[12px] text-[color:var(--color-ink-faint)]">
        思考过程（{text.length} 字）
      </summary>
      <p className="mt-1.5 whitespace-pre-wrap text-[12px] leading-relaxed text-[color:var(--color-ink-dim)]">
        {text}
      </p>
    </details>
  );
}

// ———— 消息行 ————

export function UserMessage({ content }: { content: string }) {
  return (
    <div className="animate-rise my-5 flex justify-end">
      <div className="max-w-[78%] rounded-xl rounded-tr-sm border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-2.5 text-[14px] leading-relaxed text-[color:var(--color-ink)]">
        {content}
      </div>
    </div>
  );
}

export function ReasoningMessage({ text }: { text: string }) {
  return (
    <div className="animate-rise my-5 flex gap-3.5">
      <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] text-[13px] text-[color:var(--color-accent)]">
        和
      </span>
      <div className="min-w-0 flex-1">
        <Reasoning text={text} />
      </div>
    </div>
  );
}

/** ag-ui 的 role:"tool" 消息（配对键 toolCallId，@ag-ui/core ToolMessage 的本地形态） */
export type ToolMessage = Extract<Message, { role: "tool" }>;

/** role:"tool" 消息按 toolCallId 建索引，供工具卡渲染时配对取 result（FR-7）。 */
export function buildToolMessageMap(messages: Message[]): Map<string, ToolMessage> {
  const map = new Map<string, ToolMessage>();
  for (const m of messages) if (m.role === "tool" && m.toolCallId) map.set(m.toolCallId, m);
  return map;
}

// memo 隔离历史消息重渲染：流式期间 agent.messages 高频变化，
// 已完成的历史消息 props 不变时不重渲染（Markdown 解析是主要开销）。
// 比较器必须自定义：messages 每次变化都会重建 toolMessageByCallId（Map 新引用），
// 默认浅比较会让全部历史消息在每次流式 delta 重渲染，废掉本隔离。
export const AssistantMessage = memo(function AssistantMessage({
  message,
  toolMessageByCallId,
}: {
  message: Message;
  toolMessageByCallId: Map<string, ToolMessage>;
}) {
  const renderToolCall = useRenderToolCall();
  const toolCalls = message.role === "assistant" ? (message.toolCalls ?? []) : [];
  const content = typeof message.content === "string" ? message.content : "";

  return (
    <div className="animate-rise my-5 flex gap-3.5">
      <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] text-[13px] text-[color:var(--color-accent)]">
        和
      </span>
      <div className="min-w-0 flex-1">
        {toolCalls.map((tc) => (
          // FR-7：complete 态依赖 toolMessage 配对（官方 CopilotChatToolCallsView 范式，05 §4.2）
          <div key={tc.id}>
            {renderToolCall({ toolCall: tc, toolMessage: toolMessageByCallId.get(tc.id) })}
          </div>
        ))}
        {/* MS-29 F2：信任渲染入口——TrustMessageContent 独立订阅 trustStore（useSyncExternalStore
            引用稳定契约），有锚渲染角标、无锚走原 MarkdownView；store 事件不触发本 memo 组件
            重渲染（比较器无需 trust 项，F1 契约建议形态）。 */}
        {content && <TrustMessageContent messageId={message.id} content={content} />}
        {/* MS-29 F3：低置信横幅 + disclaimer——TrustMessageAdvisories 与 F2 同消息级独立订阅，
            插在正文与反馈条之间；两组件并存不互斥，payload 无 confidence/advice 键零渲染。 */}
        <TrustMessageAdvisories messageId={message.id} />
        <FeedbackBar messageId={message.id} />
      </div>
    </div>
  );
}, (prev, next) => {
  if (prev.message !== next.message) return false;
  const tcs = next.message.role === "assistant" ? (next.message.toolCalls ?? []) : [];
  // 只有本条消息涉及的 toolMessage 变化才重渲染
  return tcs.every(
    (tc) => prev.toolMessageByCallId.get(tc.id) === next.toolMessageByCallId.get(tc.id),
  );
});
