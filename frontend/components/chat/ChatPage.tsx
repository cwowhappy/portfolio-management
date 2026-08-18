"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { runAgent } from "@/lib/agui";
import { fetchHealth } from "@/lib/api";
import {
  deleteSession,
  listSessions,
  loadMessages,
  newThreadId,
  saveMessages,
  type SessionMeta,
} from "@/lib/sessions";
import type { AguiEvent, ChatMessage, RunAgentInput } from "@/lib/types";
import Composer from "./Composer";
import Markdown from "./Markdown";
import Sidebar from "./Sidebar";
import ToolCallCard, { type ToolCallState } from "./ToolCallCard";

interface StreamState {
  thinking: string;
  text: string;
  tools: ToolCallState[];
  error: string | null;
  finished: boolean;
}

const EMPTY_STREAM: StreamState = {
  thinking: "",
  text: "",
  tools: [],
  error: null,
  finished: true,
};

const EXAMPLES = [
  "帮我看看贵州茅台最近的走势和估值",
  "今天大盘表现怎么样？",
  "搜索一下宁德时代最近的新闻",
];

const MAX_HISTORY = 40;

function toolResultText(content: unknown): { text: string; isError: boolean } {
  const raw = typeof content === "string" ? content : JSON.stringify(content ?? "");
  const isError = raw.includes('"error"');
  return { text: raw.length > 2000 ? raw.slice(0, 2000) + "…" : raw, isError };
}

export default function ChatPage() {
  const [sessions, setSessions] = useState<SessionMeta[]>([]);
  const [activeId, setActiveId] = useState<string | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [stream, setStream] = useState<StreamState>(EMPTY_STREAM);
  const [health, setHealth] = useState<{ llmKey: boolean; marketOk: boolean } | null>(null);
  const [busy, setBusy] = useState(false);
  const abortRef = useRef<AbortController | null>(null);
  const streamRef = useRef<StreamState>(EMPTY_STREAM);
  const bottomRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    streamRef.current = stream;
  }, [stream]);

  useEffect(() => {
    setSessions(listSessions());
    fetchHealth()
      .then((h) => setHealth({ llmKey: h.llm.keyConfigured, marketOk: h.market.ok }))
      .catch(() => setHealth(null));
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth" });
  }, [messages, stream]);

  const selectSession = useCallback((id: string) => {
    setActiveId(id);
    setMessages(loadMessages(id));
    setStream(EMPTY_STREAM);
  }, []);

  const startNew = useCallback(() => {
    setActiveId(newThreadId());
    setMessages([]);
    setStream(EMPTY_STREAM);
  }, []);

  const removeSession = useCallback(
    (id: string) => {
      deleteSession(id);
      setSessions(listSessions());
      if (id === activeId) startNew();
    },
    [activeId, startNew],
  );

  const stop = useCallback(() => {
    abortRef.current?.abort();
  }, []);

  const handleEvent = useCallback((e: AguiEvent) => {
    setStream((s) => {
      switch (e.type) {
        case "REASONING_MESSAGE_CONTENT":
          return { ...s, thinking: s.thinking + (e.delta ?? "") };
        case "TEXT_MESSAGE_START":
          return { ...s, text: s.text ? s.text + "\n\n" : s.text };
        case "TEXT_MESSAGE_CONTENT":
          return { ...s, text: s.text + (e.delta ?? "") };
        case "TOOL_CALL_START": {
          if (s.tools.some((t) => t.toolCallId === e.toolCallId)) return s;
          return {
            ...s,
            tools: [
              ...s.tools,
              {
                toolCallId: String(e.toolCallId ?? ""),
                name: String(e.toolCallName ?? "tool"),
                args: "",
                result: null,
                status: "running",
              },
            ],
          };
        }
        case "TOOL_CALL_ARGS":
          return {
            ...s,
            tools: s.tools.map((t) =>
              t.toolCallId === e.toolCallId ? { ...t, args: t.args + (e.delta ?? "") } : t,
            ),
          };
        case "TOOL_CALL_RESULT": {
          const { text, isError } = toolResultText(e.content);
          return {
            ...s,
            tools: s.tools.map((t) =>
              t.toolCallId === e.toolCallId
                ? { ...t, result: text, status: isError ? "error" : "done" }
                : t,
            ),
          };
        }
        case "RUN_ERROR":
          return {
            ...s,
            error: String(e.message ?? "运行出错，请重试"),
            finished: true,
          };
        case "RAW": {
          // AgentScope 未映射事件（如 Agent not found）的兜底展示
          const inner = e.event as Record<string, unknown> | undefined;
          if (inner && typeof inner.error === "string") {
            return { ...s, error: inner.error, finished: true };
          }
          return s;
        }
        case "RUN_FINISHED":
          return { ...s, finished: true };
        default:
          return s;
      }
    });
  }, []);

  const send = useCallback(
    async (text: string) => {
      if (busy || !activeId) return;
      const userMsg: ChatMessage = {
        id: crypto.randomUUID(),
        role: "user",
        content: text,
        createdAt: Date.now(),
      };
      const nextMessages = [...messages, userMsg];
      setMessages(nextMessages);
      saveMessages(activeId, nextMessages);
      setStream({ thinking: "", text: "", tools: [], error: null, finished: false });
      setBusy(true);

      const history: RunAgentInput["messages"] = nextMessages
        .slice(-MAX_HISTORY)
        .map((m) => ({ id: m.id, role: m.role, content: m.content }));
      const input: RunAgentInput = {
        threadId: activeId,
        runId: crypto.randomUUID(),
        messages: history,
        state: {},
        tools: [],
        forwardedProps: {},
      };

      const controller = new AbortController();
      abortRef.current = controller;

      try {
        await runAgent(input, handleEvent, controller.signal);
      } catch (err) {
        const message = err instanceof Error ? err.message : "请求失败，请稍后重试";
        setStream((s) => ({ ...s, error: message, finished: true }));
      } finally {
        setBusy(false);
        abortRef.current = null;
      }

      // 收尾：把流式文本固化为 assistant 消息
      const finalStream = streamRef.current;
      const finalText = finalStream.text.trim();
      const finalMessages = [...nextMessages];
      if (finalText) {
        finalMessages.push({
          id: crypto.randomUUID(),
          role: "assistant",
          content: finalText,
          createdAt: Date.now(),
        });
      }
      if (finalStream.error) {
        finalMessages.push({
          id: crypto.randomUUID(),
          role: "assistant",
          content: "⚠️ " + finalStream.error,
          createdAt: Date.now(),
        });
      }
      setMessages(finalMessages);
      saveMessages(activeId, finalMessages);
      setSessions(listSessions());
    },
    [activeId, busy, handleEvent, messages],
  );

  const streaming = !stream.finished;

  return (
    <div className="flex h-full">
      <Sidebar
        sessions={sessions}
        activeId={activeId}
        health={health}
        onSelect={selectSession}
        onNew={startNew}
        onDelete={removeSession}
      />

      <section className="flex min-w-0 flex-1 flex-col">
        <div className="flex-1 overflow-y-auto">
          <div className="mx-auto max-w-[860px] px-5 py-8">
            {messages.length === 0 && !streaming && (
              <div className="animate-rise flex flex-col items-center pt-[12vh] text-center">
                <p className="font-[family-name:var(--font-display)] text-[26px] leading-snug tracking-wide text-[color:var(--color-ink)]">
                  问行情 · 看走势 · 读财报
                </p>
                <p className="mt-3 max-w-md text-[13px] leading-relaxed text-[color:var(--color-ink-dim)]">
                  基于实时行情数据与 DeepSeek 大模型的 A股投研助手。
                  Agent 会自动调用行情、财务与新闻工具，为你整理数据并给出分析。
                </p>
                {health && !health.llmKey && (
                  <p className="mt-5 max-w-md rounded-lg border border-[color:var(--color-amber)]/40 bg-[color:var(--color-panel)] px-4 py-3 text-[12px] leading-relaxed text-[color:var(--color-amber)]">
                    未检测到 DEEPSEEK_API_KEY：对话功能暂不可用。请在 backend 的 .env
                    中配置后重启服务；行情数据页仍可正常浏览。
                  </p>
                )}
                <div className="mt-8 flex flex-wrap justify-center gap-2.5">
                  {EXAMPLES.map((ex) => (
                    <button
                      key={ex}
                      type="button"
                      disabled={!!(health && !health.llmKey)}
                      onClick={() => send(ex)}
                      className="rounded-full border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-4 py-2 text-[13px] text-[color:var(--color-ink-dim)] transition-all hover:border-[color:var(--color-up)] hover:text-[color:var(--color-ink)] disabled:opacity-40"
                    >
                      {ex}
                    </button>
                  ))}
                </div>
              </div>
            )}

            {messages.map((m) => (
              <div
                key={m.id}
                className={"animate-rise my-5 " + (m.role === "user" ? "flex justify-end" : "")}
              >
                {m.role === "user" ? (
                  <div className="max-w-[78%] rounded-xl rounded-tr-sm border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-2.5 text-[14px] leading-relaxed text-[color:var(--color-ink)]">
                    {m.content}
                  </div>
                ) : (
                  <div className="flex gap-3.5">
                    <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] text-[13px] text-[color:var(--color-up)]">
                      砚
                    </span>
                    <div className="min-w-0 flex-1">
                      {m.content.startsWith("⚠️") ? (
                        <p className="text-[13px] text-[color:var(--color-amber)]">{m.content}</p>
                      ) : (
                        <Markdown content={m.content} />
                      )}
                    </div>
                  </div>
                )}
              </div>
            ))}

            {streaming && (
              <div className="my-5">
                <div className="flex gap-3.5">
                  <span className="mt-0.5 grid h-7 w-7 shrink-0 place-items-center rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] text-[13px] text-[color:var(--color-up)]">
                    砚
                  </span>
                  <div className="min-w-0 flex-1">
                    {stream.thinking && (
                      <details className="mb-2 rounded-md border border-[color:var(--color-line-soft)] bg-[color:var(--color-bg-soft)] px-3 py-2">
                        <summary className="cursor-pointer select-none text-[12px] text-[color:var(--color-ink-faint)]">
                          思考过程（{stream.thinking.length} 字）
                        </summary>
                        <p className="mt-1.5 whitespace-pre-wrap text-[12px] leading-relaxed text-[color:var(--color-ink-dim)]">
                          {stream.thinking}
                        </p>
                      </details>
                    )}
                    {stream.tools.map((t) => (
                      <ToolCallCard key={t.toolCallId} tool={t} />
                    ))}
                    {stream.text ? (
                      <Markdown content={stream.text} />
                    ) : stream.tools.length === 0 && !stream.thinking ? (
                      <p className="skeleton h-4 w-2/3" />
                    ) : null}
                    <span className="animate-caret ml-0.5 inline-block h-4 w-[7px] translate-y-[3px] bg-[color:var(--color-up)]" />
                  </div>
                </div>
              </div>
            )}
            <div ref={bottomRef} />
          </div>
        </div>

        <Composer
          disabled={!!(health && !health.llmKey)}
          streaming={streaming}
          onSend={send}
          onStop={stop}
        />
      </section>
    </div>
  );
}
