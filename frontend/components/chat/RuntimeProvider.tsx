"use client";

import type { Message } from "@ag-ui/client";
import {
  createContext,
  useCallback,
  useContext,
  useState,
  type ReactNode,
} from "react";
import {
  deleteSession,
  listSessions,
  loadMessages,
  newThreadId,
  saveMessages,
  type SessionMeta,
} from "@/lib/sessions";
import type { ChatMessage } from "@/lib/types";

export const AGENT_ID = "invest";

// useAgent 的 thread 作用域三件套（agentId/runtimeAgentId/threadId）必填；
// agentId 必须是每个线程唯一的本地注册名，runtimeAgentId 才指向运行时 agent。
export function localAgentId(threadId: string): string {
  return `${AGENT_ID}:${threadId}`;
}

// ———— 会话上下文（Sidebar / ThreadArea 读取） ————

interface ChatRuntimeContextValue {
  sessions: SessionMeta[];
  currentThreadId: string;
  newThread: () => void;
  switchThread: (threadId: string) => void;
  deleteThread: (threadId: string) => void;
  persistMessages: (threadId: string, msgs: ChatMessage[]) => void;
}

const ChatRuntimeContext = createContext<ChatRuntimeContextValue | null>(null);

export function useChatRuntime(): ChatRuntimeContextValue {
  const ctx = useContext(ChatRuntimeContext);
  if (!ctx) throw new Error("useChatRuntime 必须在 RuntimeProvider 内使用");
  return ctx;
}

// ———— 历史格式转换（ChatMessage ↔ AG-UI Message） ————

export function historyToAgentMessages(msgs: ChatMessage[]): Message[] {
  return msgs.map(
    (m): Message =>
      m.role === "user"
        ? { id: m.id, role: "user", content: m.content }
        : { id: m.id, role: "assistant", content: m.content },
  );
}

export function agentMessagesToHistory(messages: Message[]): ChatMessage[] {
  const out: ChatMessage[] = [];
  for (const m of messages) {
    if (m.role !== "user" && m.role !== "assistant") continue;
    const content = typeof m.content === "string" ? m.content.trim() : "";
    if (!content) continue;
    out.push({ id: m.id, role: m.role, content, createdAt: Date.now() });
  }
  return out;
}

// ———— Provider ————

export function RuntimeProvider({ children }: { children: ReactNode }) {
  const [sessions, setSessions] = useState<SessionMeta[]>(() => listSessions());
  const [currentThreadId, setCurrentThreadId] = useState<string>(() => {
    const list = listSessions();
    return list.length > 0 ? list[0].id : newThreadId();
  });

  const refresh = useCallback(() => setSessions(listSessions()), []);

  const newThread = useCallback(() => setCurrentThreadId(newThreadId()), []);
  const switchThread = useCallback((id: string) => setCurrentThreadId(id), []);
  const deleteThread = useCallback(
    (id: string) => {
      deleteSession(id);
      refresh();
      if (id === currentThreadId) setCurrentThreadId(newThreadId());
    },
    [currentThreadId, refresh],
  );

  const persistMessages = useCallback(
    (threadId: string, msgs: ChatMessage[]) => {
      saveMessages(threadId, msgs);
      refresh();
    },
    [refresh],
  );

  const value = {
    sessions,
    currentThreadId,
    newThread,
    switchThread,
    deleteThread,
    persistMessages,
  };

  return (
    <ChatRuntimeContext.Provider value={value}>
      {children}
    </ChatRuntimeContext.Provider>
  );
}
