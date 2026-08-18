"use client";

import {
  MessagePrimitive,
  ThreadPrimitive,
  ComposerPrimitive,
  type TextMessagePartProps,
  type ReasoningMessagePartProps,
  type ToolCallMessagePartProps,
} from "@assistant-ui/react";
import remarkGfm from "remark-gfm";
import { MarkdownTextPrimitive } from "@assistant-ui/react-markdown";
import ToolCallCard from "./ToolCallCard";

// ———— 消息部件（保留“研报终端”设计体系） ————

function TextPart({ status }: TextMessagePartProps) {
  return (
    <>
      {/* 官方 Markdown 部件：读当前 text part 上下文，内置平滑流式 */}
      <MarkdownTextPrimitive
        className="md-body"
        defer
        remarkPlugins={[remarkGfm]}
      />
      {status?.type === "running" && (
        <span className="animate-caret ml-0.5 inline-block h-4 w-[7px] translate-y-[3px] bg-[color:var(--color-up)]" />
      )}
    </>
  );
}

function ReasoningPart({ text }: ReasoningMessagePartProps) {
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

function ToolCallPart(props: ToolCallMessagePartProps) {
  return <ToolCallCard {...props} />;
}

const assistantParts: React.ComponentProps<typeof MessagePrimitive.Parts>["components"] = {
  Text: TextPart,
  Reasoning: ReasoningPart,
  tools: { Fallback: ToolCallPart },
  Empty: () => <span className="skeleton inline-block h-4 w-2/3" />,
};

// ———— 消息行 ————

function UserMessage() {
  return (
    <div className="animate-rise my-5 flex justify-end">
      <MessagePrimitive.Parts
        components={{
          Text: ({ text }: TextMessagePartProps) => (
            <div className="max-w-[78%] rounded-xl rounded-tr-sm border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-2.5 text-[14px] leading-relaxed text-[color:var(--color-ink)]">
              {text}
            </div>
          ),
        }}
      />
    </div>
  );
}

function AssistantMessage() {
  return (
    <div className="animate-rise my-5 flex gap-3.5">
      <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] text-[13px] text-[color:var(--color-up)]">
        砚
      </span>
      <div className="min-w-0 flex-1">
        <MessagePrimitive.Parts components={assistantParts} />
      </div>
    </div>
  );
}

// ———— 空状态 ————

const EXAMPLES = [
  "帮我看看贵州茅台最近的走势和估值",
  "今天大盘表现怎么样？",
  "搜索一下宁德时代最近的新闻",
];

function EmptyState({ llmReady }: { llmReady: boolean }) {
  return (
    <div className="animate-rise flex flex-col items-center pt-[10vh] text-center">
      <p className="font-[family-name:var(--font-display)] text-[26px] leading-snug tracking-wide text-[color:var(--color-ink)]">
        问行情 · 看走势 · 读财报
      </p>
      <p className="mt-3 max-w-md text-[13px] leading-relaxed text-[color:var(--color-ink-dim)]">
        基于实时行情数据与 DeepSeek 大模型的 A股投研助手。
        Agent 会自动调用行情、财务与新闻工具，为你整理数据并给出分析。
      </p>
      {!llmReady && (
        <p className="mt-5 max-w-md rounded-lg border border-[color:var(--color-amber)]/40 bg-[color:var(--color-panel)] px-4 py-3 text-[12px] leading-relaxed text-[color:var(--color-amber)]">
          未检测到 DEEPSEEK_API_KEY：对话功能暂不可用。请在 backend 的 .env
          中配置后重启服务；行情数据页仍可正常浏览。
        </p>
      )}
      <div className="mt-8 flex flex-wrap justify-center gap-2.5">
        {EXAMPLES.map((prompt) => (
          <ThreadPrimitive.Suggestion
            key={prompt}
            prompt={prompt}
            autoSend
            className="rounded-full border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-4 py-2 text-[13px] text-[color:var(--color-ink-dim)] transition-all hover:border-[color:var(--color-up)] hover:text-[color:var(--color-ink)]"
          >
            {prompt}
          </ThreadPrimitive.Suggestion>
        ))}
      </div>
    </div>
  );
}

// ———— 输入区 ————

function Composer() {
  return (
    <ComposerPrimitive.Root className="border-t border-[color:var(--color-line)] bg-[color:var(--color-bg)]/80 px-4 pb-4 pt-3 backdrop-blur-sm">
      <div className="composer mx-auto max-w-[860px] p-2">
        <ComposerPrimitive.Input
          rows={1}
          autoFocus
          placeholder="问行情、看走势、读财报… 例如：帮我看看贵州茅台最近的走势和估值"
          onInput={(e) => {
            const el = e.currentTarget;
            el.style.height = "auto";
            el.style.height = Math.min(el.scrollHeight, 160) + "px";
          }}
          className="max-h-40 w-full resize-none bg-transparent px-2.5 py-1.5 text-[14px] leading-relaxed text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:outline-none"
        />
        <div className="flex items-center justify-between px-1.5 pb-0.5 pt-1.5">
          <span className="text-[11px] text-[color:var(--color-ink-faint)]">
            Enter 发送 · Shift+Enter 换行 · 回答由 DeepSeek 生成
          </span>
          <ThreadPrimitive.If running>
            <ComposerPrimitive.Cancel className="rounded-md border border-[color:var(--color-line)] px-3 py-1 text-[12px] text-[color:var(--color-ink-dim)] transition-colors hover:border-[color:var(--color-up)] hover:text-[color:var(--color-up)]">
              ■ 停止
            </ComposerPrimitive.Cancel>
          </ThreadPrimitive.If>
          <ThreadPrimitive.If running={false}>
            <ComposerPrimitive.Send className="rounded-md bg-[color:var(--color-up)] px-4 py-1 text-[12px] text-white transition-all enabled:hover:brightness-110 disabled:opacity-30">
              发送
            </ComposerPrimitive.Send>
          </ThreadPrimitive.If>
        </div>
      </div>
    </ComposerPrimitive.Root>
  );
}

// ———— 会话区 ————

export default function ThreadArea({ llmReady }: { llmReady: boolean }) {
  return (
    <ThreadPrimitive.Root className="flex h-full min-w-0 flex-1 flex-col">
      <ThreadPrimitive.Viewport className="flex-1 overflow-y-auto">
        <div className="mx-auto max-w-[860px] px-5 py-8">
          <ThreadPrimitive.Empty>
            <EmptyState llmReady={llmReady} />
          </ThreadPrimitive.Empty>
          <ThreadPrimitive.Messages
            components={{
              UserMessage,
              AssistantMessage,
            }}
          />
          <ThreadPrimitive.ScrollToBottom>
            <div className="h-6" />
          </ThreadPrimitive.ScrollToBottom>
        </div>
      </ThreadPrimitive.Viewport>
      <Composer />
    </ThreadPrimitive.Root>
  );
}
