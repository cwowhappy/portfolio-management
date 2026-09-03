"use client";

import {
  useAgent,
  useCopilotKit,
  useDefaultRenderTool,
  useRenderToolCall,
  UseAgentUpdate,
} from "@copilotkit/react-core/v2";
import type { Message } from "@ag-ui/client";
import { memo, useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import {
  AGENT_ID,
  agentMessagesToHistory,
  historyToAgentMessages,
  useChatRuntime,
} from "./RuntimeProvider";
import { loadMessages, newThreadId } from "@/lib/conversations";
import ToolCallCard from "./ToolCallCard";
import { CodeBlock, InlineCode } from "./CodeHighlight";

// 模块级常量：避免每次渲染新建数组触发潜在的重订阅
const AGENT_UPDATES = [UseAgentUpdate.OnMessagesChanged, UseAgentUpdate.OnRunStatusChanged];

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

// ———— 工具卡通配渲染器（通配兜底） ————

function ToolCallRenderers() {
  useDefaultRenderTool({
    render: ({ name, toolCallId, parameters, status, result }) => (
      <ToolCallCard
        toolCallId={toolCallId}
        toolName={name}
        parameters={parameters}
        status={status}
        result={result}
      />
    ),
  });
  return null;
}

// ———— 反馈 ————

// localStorage 反馈键容量上限：超出时清最旧，避免无限增长
const FEEDBACK_KEY_PREFIX = "invest.feedback.";
const FEEDBACK_MAX_ENTRIES = 200;

/** 反馈键数量达到上限时，按写入时间清掉最旧的，腾出位置给新反馈。 */
function pruneFeedbackKeys() {
  const entries: { key: string; at: number }[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key?.startsWith(FEEDBACK_KEY_PREFIX)) continue;
    let at = 0;
    try {
      at = (JSON.parse(localStorage.getItem(key) ?? "{}") as { at?: number }).at ?? 0;
    } catch {
      // 损坏数据视为最旧，优先清掉
    }
    entries.push({ key, at });
  }
  entries.sort((a, b) => a.at - b.at);
  while (entries.length >= FEEDBACK_MAX_ENTRIES) {
    const oldest = entries.shift();
    if (oldest) localStorage.removeItem(oldest.key);
  }
}

function FeedbackBar({ messageId }: { messageId: string }) {
  const [voted, setVoted] = useState<"positive" | "negative" | null>(null);
  useEffect(() => {
    // localStorage 仅客户端可读，必须挂载后恢复；微任务 defer 避免 effect 体内同步 setState
    // （同步读会破坏服务端/客户端首帧一致性，见规范 4.3）
    let cancelled = false;
    void Promise.resolve().then(() => {
      if (cancelled) return;
      try {
        const raw = localStorage.getItem(FEEDBACK_KEY_PREFIX + messageId);
        if (raw) setVoted((JSON.parse(raw) as { type: "positive" | "negative" }).type);
      } catch {
        // ignore
      }
    });
    return () => {
      cancelled = true;
    };
  }, [messageId]);
  const vote = (type: "positive" | "negative") => {
    try {
      pruneFeedbackKeys();
      localStorage.setItem(
        FEEDBACK_KEY_PREFIX + messageId,
        JSON.stringify({ type, at: Date.now() }),
      );
    } catch {
      // ignore
    }
    setVoted(type);
  };
  return (
    <div className="mt-1.5 flex items-center gap-1 opacity-35 transition-opacity hover:opacity-100">
      <button
        aria-label="回答有帮助"
        aria-pressed={voted === "positive"}
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
        aria-pressed={voted === "negative"}
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

function ReasoningMessage({ text }: { text: string }) {
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

// memo 隔离历史消息重渲染：流式期间 agent.messages 高频变化，
// 已完成的历史消息 props 不变时不重渲染（Markdown 解析是主要开销）
const AssistantMessage = memo(function AssistantMessage({ message }: { message: Message }) {
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
          <div key={tc.id}>{renderToolCall({ toolCall: tc })}</div>
        ))}
        {content && (
          <div className="md-body text-[14px] leading-relaxed text-[color:var(--color-ink)]">
            <ReactMarkdown
              remarkPlugins={[remarkGfm]}
              components={{
                pre: (p) => <>{p.children}</>,
                code: ({ className, children }) => {
                  // 有语言类名，或含换行（无语言围栏代码块）均按块渲染
                  const isBlock =
                    /language-[\w-]+/.test(className ?? "") ||
                    String(children ?? "").includes("\n");
                  return isBlock ? (
                    <CodeBlock className={className}>{children}</CodeBlock>
                  ) : (
                    <InlineCode>{children}</InlineCode>
                  );
                },
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
});

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
        <p className="mt-5 max-w-md rounded-lg border border-[color:var(--color-accent)]/40 bg-[color:var(--color-panel)] px-4 py-3 text-[12px] leading-relaxed text-[color:var(--color-accent)]">
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
  ready,
  onSend,
  onStop,
}: {
  isRunning: boolean;
  ready: boolean;
  onSend: (text: string) => void;
  onStop: () => void;
}) {
  const [draft, setDraft] = useState("");
  const taRef = useRef<HTMLTextAreaElement>(null);
  const submit = () => {
    const t = draft.trim();
    if (!t || isRunning || !ready) return;
    setDraft("");
    if (taRef.current) taRef.current.style.height = "auto"; // 重置自增高
    onSend(t);
  };
  return (
    <div className="border-t border-[color:var(--color-line)] bg-[color:var(--color-bg)]/80 px-4 pb-4 pt-3 backdrop-blur-sm">
      <div className="composer mx-auto max-w-[860px] p-2">
        <textarea
          ref={taRef}
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
              disabled={!ready || !draft.trim()}
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
  const { currentThreadId, persistMessages, setRunning } = useChatRuntime();
  const { agent, isReady } = useAgent({
    agentId: AGENT_ID,
    updates: AGENT_UPDATES,
    // 流式期间合并高频 OnMessagesChanged 通知，降低整树重渲染频率
    throttleMs: 150,
  });
  const { copilotkit } = useCopilotKit();
  const [sendError, setSendError] = useState<string | null>(null);
  const persistTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 防抖窗口内待写入的快照：供「运行停止立即 flush」与「卸载/切线程 best-effort flush」使用
  const pendingPersist = useRef<{ threadId: string; msgs: Message[] } | null>(null);
  // 最新 currentThreadId：供防抖定时器到期 / 运行停止 flush 前校验 pending 是否已过期（双保险）
  const currentThreadIdRef = useRef(currentThreadId);
  // agent.messages 当前内容归属的线程：成功回灌某线程历史后更新。为 null 表示尚未回灌任何线程。
  const hydratedThreadIdRef = useRef<string | null>(null);

  // 同步最新线程到 ref，供异步 flush 前比对 pending.threadId
  useEffect(() => {
    currentThreadIdRef.current = currentThreadId;
  }, [currentThreadId]);

  // 同步运行状态到 Provider，供 Sidebar 在运行中禁用切换/新建
  useEffect(() => {
    setRunning(agent.isRunning);
  }, [agent.isRunning, setRunning]);

  const send = useCallback(
    async (text: string) => {
      const t = text.trim();
      if (!t || agent.isRunning || !isReady) return;
      setSendError(null);
      agent.addMessage({ id: newThreadId(), role: "user", content: t });
      try {
        await copilotkit.runAgent({ agent });
      } catch (e) {
        setSendError(e instanceof Error ? e.message : "请求失败，请稍后重试");
      }
    },
    [agent, copilotkit, isReady],
  );

  // 历史回灌：真实 agent 就绪后，把服务端历史种回去（后端 server-side-memory=false）。
  // 仅在回灌成功后把 hydratedThreadIdRef 指向当前线程：此前 agent.messages 仍属于旧线程，
  // 防抖持久化必须等回灌完成（或至少不把旧线程内容写进新线程）后再启动。
  useEffect(() => {
    if (!isReady) return;
    let cancelled = false;
    const threadId = currentThreadId;
    (async () => {
      try {
        const history = await loadMessages(threadId);
        if (cancelled) return;
        if (agent.isRunning) agent.abortRun(); // 切换线程时停止旧流，避免跨线程串写
        agent.setMessages(historyToAgentMessages(history));
        hydratedThreadIdRef.current = threadId;
      } catch (e) {
        if (!cancelled) console.error("[ThreadArea] 加载会话历史失败", threadId, e);
        // 回灌失败不 setMessages：hydrated 仍指向旧线程，防抖不会把旧内容写进本线程
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [agent, isReady, currentThreadId]);

  // 执行一次持久化。msgs 是调用点快照：若等待 loadMessages 期间发生切线程 + 历史回灌
  // setMessages，agent.messages 会变成新线程的，若不快照会把新线程消息写进旧线程记录。
  // keepalive 用于卸载场景：让 PUT 在页面销毁后仍能完成。
  const flushPersist = useCallback(
    (threadId: string, msgs: Message[], keepalive = false) => {
      (async () => {
        try {
          const saved = agentMessagesToHistory(msgs, await loadMessages(threadId));
          if (saved.length > 0) await persistMessages(threadId, saved, { keepalive });
        } catch (e) {
          console.error("[ThreadArea] 持久化会话失败", threadId, e);
        }
      })();
    },
    [persistMessages],
  );

  // 消息变化 → 防抖持久化（流式期间高频触发，避免每个 token 都整段重写；400ms 后先拉取现有历史再整体 PUT）
  useEffect(() => {
    // 双保险 (1)：切线程/历史回灌完成前，agent.messages 仍是旧线程内容，不启动新线程的防抖持久化，
    // 否则 400ms 后 flushPersist(newThreadId, oldMsgs) 会把旧会话内容 PUT 覆盖新线程服务端记录。
    const hydrated = hydratedThreadIdRef.current;
    if (hydrated !== null && hydrated !== currentThreadId) {
      pendingPersist.current = null;
      return;
    }
    pendingPersist.current = { threadId: currentThreadId, msgs: agent.messages ?? [] };
    if (persistTimer.current) clearTimeout(persistTimer.current);
    persistTimer.current = setTimeout(() => {
      persistTimer.current = null;
      const pending = pendingPersist.current;
      pendingPersist.current = null;
      // 双保险 (2)：防抖到期时线程已切换（pending.threadId 落后于 currentThreadId）则丢弃快照。
      // 旧线程快照的落库由「卸载/切线程 cleanup」以 keepalive flush 负责。
      if (pending && pending.threadId === currentThreadIdRef.current) {
        flushPersist(pending.threadId, pending.msgs);
      }
    }, 400);
    return () => {
      if (persistTimer.current) {
        clearTimeout(persistTimer.current);
        persistTimer.current = null;
      }
    };
  }, [agent.messages, currentThreadId, flushPersist]);

  // 运行结束（isRunning true→false）立即 flush：防抖窗口内的尾部内容不等 400ms，避免停止即丢尾
  const prevRunning = useRef(agent.isRunning);
  useEffect(() => {
    const wasRunning = prevRunning.current;
    prevRunning.current = agent.isRunning;
    if (!wasRunning || agent.isRunning) return;
    if (persistTimer.current) {
      clearTimeout(persistTimer.current);
      persistTimer.current = null;
    }
    const pending = pendingPersist.current;
    pendingPersist.current = null;
    // 运行期间 RuntimeProvider 已禁止切线程，此处校验 pending.threadId === 当前线程为双保险
    if (pending && pending.threadId === currentThreadIdRef.current) {
      flushPersist(pending.threadId, pending.msgs);
    }
  }, [agent.isRunning, flushPersist]);

  // 卸载/切换线程时，把仍在防抖窗口内的 pending 写入 best-effort flush（keepalive 保证请求送达）。
  // 声明在防抖 effect 之后：cleanup 按声明顺序执行，先停掉旧定时器，再 flush 旧线程快照。
  useEffect(() => {
    return () => {
      const pending = pendingPersist.current;
      if (pending && pending.threadId === currentThreadId) {
        pendingPersist.current = null;
        flushPersist(pending.threadId, pending.msgs, true);
      }
    };
  }, [currentThreadId, flushPersist]);

  const messages = agent.messages ?? [];
  const isEmpty = messages.length === 0;

  return (
    <div className="flex h-full min-w-0 flex-1 flex-col">
      <ToolCallRenderers />
      <div className="flex-1 overflow-y-auto">
        <div className="mx-auto max-w-[860px] px-5 py-8" aria-live="polite">
          {isEmpty ? (
            <EmptyState llmReady={llmReady} onPick={(p) => void send(p)} />
          ) : (
            messages.map((m) => {
              if (m.role === "user") {
                return (
                  <UserMessage
                    key={m.id}
                    content={typeof m.content === "string" ? m.content : ""}
                  />
                );
              }
              if (m.role === "assistant") {
                return <AssistantMessage key={m.id} message={m} />;
              }
              if (m.role === "reasoning") {
                return <ReasoningMessage key={m.id} text={m.content} />;
              }
              return null;
            })
          )}
          <div className="h-6" />
        </div>
      </div>
      {sendError && (
        <div className="mx-auto w-full max-w-[860px] px-5 pb-2">
          <p className="flex items-center justify-between rounded-lg border border-[color:var(--color-accent)]/40 bg-[color:var(--color-panel)] px-4 py-2.5 text-[13px] text-[color:var(--color-accent)]">
            <span>{sendError}</span>
            <button
              onClick={() => setSendError(null)}
              aria-label="关闭错误提示"
              className="ml-3 text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-ink)]"
            >
              ✕
            </button>
          </p>
        </div>
      )}
      <Composer
        isRunning={agent.isRunning}
        ready={isReady}
        onSend={(t) => void send(t)}
        onStop={() => agent.abortRun()}
      />
    </div>
  );
}
