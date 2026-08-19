"use client";

import { useAgent, useCopilotKit, UseAgentUpdate } from "@copilotkit/react-core/v2";
import { useCallback, useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { AGENT_ID, useChatRuntime } from "./RuntimeProvider";
import ToolCallCard from "./ToolCallCard";
import { CodeBlock, InlineCode } from "./CodeHighlight";
import type { AgentMessage } from "@/lib/types";

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

// ———— 反馈 ————

function FeedbackBar({ messageId }: { messageId: string }) {
  const [voted, setVoted] = useState<"positive" | "negative" | null>(null);
  useEffect(() => {
    try {
      const raw = localStorage.getItem("invest.feedback." + messageId);
      if (raw) setVoted((JSON.parse(raw) as { type: "positive" | "negative" }).type);
    } catch {
      // ignore
    }
  }, [messageId]);
  const vote = (type: "positive" | "negative") => {
    try {
      localStorage.setItem("invest.feedback." + messageId, JSON.stringify({ type, at: Date.now() }));
    } catch {
      // ignore
    }
    setVoted(type);
  };
  return (
    <div className="mt-1.5 flex items-center gap-1 opacity-35 transition-opacity hover:opacity-100">
      <button
        aria-label="回答有帮助"
        onClick={() => vote("positive")}
        className={
          "rounded px-1.5 py-0.5 text-[12px] transition-colors " +
          (voted === "positive" ? "text-[color:var(--color-down)]" : "text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-down)]")
        }
      >
        👍
      </button>
      <button
        aria-label="回答需要改进"
        onClick={() => vote("negative")}
        className={
          "rounded px-1.5 py-0.5 text-[12px] transition-colors " +
          (voted === "negative" ? "text-[color:var(--color-up)]" : "text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-up)]")
        }
      >
        👎
      </button>
    </div>
  );
}

// ———— 消息行 ————

function UserMessage({ content }: { content: string }) {
  return (
    <div className="animate-rise my-5 flex justify-end">
      <div className="max-w-[78%] rounded-xl rounded-tr-sm border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-2.5 text-[14px] leading-relaxed text-[color:var(--color-ink)]">
        {content}
      </div>
    </div>
  );
}

function AssistantMessage({ message }: { message: AgentMessage }) {
  const toolCalls = message.toolCalls ?? [];
  const reasoning = message.reasoning;
  const content = typeof message.content === "string" ? message.content : "";

  return (
    <div className="animate-rise my-5 flex gap-3.5">
      <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] text-[13px] text-[color:var(--color-up)]">
        砚
      </span>
      <div className="min-w-0 flex-1">
        {reasoning && <Reasoning text={reasoning} />}
        {toolCalls.map((tc) => (
          <ToolCallCard
            key={tc.id}
            toolCallId={tc.id}
            toolName={tc.name}
            argsText={tc.arguments ?? ""}
            result={tc.result}
            status={tc.status}
          />
        ))}
        {content && (
          <div className="md-body text-[14px] leading-relaxed text-[color:var(--color-ink)]">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                pre: (p) => <>{p.children}</>,
                code: ({ className, children }) =>
                  /language-[\w-]+/.test(className ?? "") ? (
                    <CodeBlock className={className}>{children}</CodeBlock>
                  ) : (
                    <InlineCode>{children}</InlineCode>
                  ),
              }}
            >
              {content}
            </ReactMarkdown>
          </div>
        )}
        <FeedbackBar messageId={message.id} />
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

function EmptyState({
  llmReady,
  onPick,
}: {
  llmReady: boolean | null;
  onPick: (prompt: string) => void;
}) {
  return (
    <div className="animate-rise flex flex-col items-center pt-[10vh] text-center">
      <p className="font-[family-name:var(--font-display)] text-[26px] leading-snug tracking-wide text-[color:var(--color-ink)]">
        问行情 · 看走势 · 读财报
      </p>
      <p className="mt-3 max-w-md text-[13px] leading-relaxed text-[color:var(--color-ink-dim)]">
        基于实时行情数据与 DeepSeek 大模型的 A股投研助手。
        Agent 会自动调用行情、财务与新闻工具，为你整理数据并给出分析。
      </p>
      {llmReady === false && (
        <p className="mt-5 max-w-md rounded-lg border border-[color:var(--color-amber)]/40 bg-[color:var(--color-panel)] px-4 py-3 text-[12px] leading-relaxed text-[color:var(--color-amber)]">
          未检测到 DEEPSEEK_API_KEY：对话功能暂不可用。请在 backend 的 .env
          中配置后重启服务；行情数据页仍可正常浏览。
        </p>
      )}
      <div className="mt-8 flex flex-wrap justify-center gap-2.5">
        {EXAMPLES.map((prompt) => (
          <button
            key={prompt}
            onClick={() => onPick(prompt)}
            className="rounded-full border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-4 py-2 text-[13px] text-[color:var(--color-ink-dim)] transition-all hover:border-[color:var(--color-up)] hover:text-[color:var(--color-ink)]"
          >
            {prompt}
          </button>
        ))}
      </div>
    </div>
  );
}

// ———— 输入区 ————

function Composer({
  isRunning,
  onSend,
  onStop,
}: {
  isRunning: boolean;
  onSend: (text: string) => void;
  onStop: () => void;
}) {
  const [draft, setDraft] = useState("");
  const submit = () => {
    const t = draft.trim();
    if (!t || isRunning) return;
    setDraft("");
    onSend(t);
  };
  return (
    <div className="border-t border-[color:var(--color-line)] bg-[color:var(--color-bg)]/80 px-4 pb-4 pt-3 backdrop-blur-sm">
      <div className="composer mx-auto max-w-[860px] p-2">
        <textarea
          rows={1}
          autoFocus
          value={draft}
          placeholder="问行情、看走势、读财报… 例如：帮我看看贵州茅台最近的走势和估值"
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              e.preventDefault();
              submit();
            }
          }}
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
          {isRunning ? (
            <button
              onClick={onStop}
              className="rounded-md border border-[color:var(--color-line)] px-3 py-1 text-[12px] text-[color:var(--color-ink-dim)] transition-colors hover:border-[color:var(--color-up)] hover:text-[color:var(--color-up)]"
            >
              ■ 停止
            </button>
          ) : (
            <button
              onClick={submit}
              disabled={!draft.trim()}
              className="rounded-md bg-[color:var(--color-up)] px-4 py-1 text-[12px] text-white transition-all enabled:hover:brightness-110 disabled:opacity-30"
            >
              发送
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

// ———— 会话区 ————

export default function ThreadArea({ llmReady }: { llmReady: boolean | null }) {
  const { currentThreadId } = useChatRuntime();
  const { agent } = useAgent({
    agentId: AGENT_ID,
    threadId: currentThreadId,
    updates: [UseAgentUpdate.OnMessagesChanged, UseAgentUpdate.OnRunStatusChanged],
  });
  const { copilotkit } = useCopilotKit();

  const send = useCallback(
    async (text: string) => {
      const t = text.trim();
      if (!t || agent.isRunning) return;
      agent.addMessage({ id: crypto.randomUUID(), role: "user", content: t });
      await copilotkit.runAgent({ agent });
    },
    [agent, copilotkit],
  );

  const messages = agent.messages ?? [];
  const isEmpty = messages.length === 0;

  return (
    <div className="flex h-full min-w-0 flex-1 flex-col">
      <div className="flex-1 overflow-y-auto">
        <div className="mx-auto max-w-[860px] px-5 py-8">
          {isEmpty ? (
            <EmptyState llmReady={llmReady} onPick={(p) => void send(p)} />
          ) : (
            messages.map((m: AgentMessage) =>
              m.role === "user" ? (
                <UserMessage key={m.id} content={String(m.content ?? "")} />
              ) : (
                <AssistantMessage key={m.id} message={m} />
              ),
            )
          )}
          <div className="h-6" />
        </div>
      </div>
      <Composer
        isRunning={agent.isRunning}
        onSend={(t) => void send(t)}
        onStop={() => agent.abortRun()}
      />
    </div>
  );
}
