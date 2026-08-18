"use client";

import {
  AssistantRuntimeProvider,
  fromThreadMessageLike,
} from "@assistant-ui/react";
import { useAgUiRuntime } from "@assistant-ui/react-ag-ui";
import { HttpAgent } from "@ag-ui/client";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
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

// ———— 会话上下文（Sidebar 等 UI 消费） ————

interface ChatRuntimeContextValue {
  sessions: SessionMeta[];
  currentThreadId: string;
  switchThread: (id: string) => void;
  newThread: () => void;
  removeThread: (id: string) => void;
}

const ChatRuntimeContext = createContext<ChatRuntimeContextValue | null>(null);

export function useChatRuntime(): ChatRuntimeContextValue {
  const ctx = useContext(ChatRuntimeContext);
  if (!ctx) throw new Error("useChatRuntime 必须在 RuntimeProvider 内使用");
  return ctx;
}

// ———— 历史格式转换 ————

function historyToThreadMessages(msgs: ChatMessage[]) {
  return msgs.map((m) =>
    fromThreadMessageLike(
      { id: m.id, role: m.role, content: m.content },
      m.id,
      { type: "complete", reason: "unknown" },
    ),
  );
}

function aguiMessageOf(m: ChatMessage) {
  return { id: m.id, role: m.role, content: m.content };
}

// ———— Provider ————

/**
 * assistant-ui AG-UI Runtime：HttpAgent 直连 /api/chat，
 * threadList 适配器对接 localStorage 会话（ADR-0004 前端持有历史）。
 */
export function RuntimeProvider({ children }: { children: ReactNode }) {
  const [sessions, setSessions] = useState<SessionMeta[]>([]);
  const [currentThreadId, setCurrentThreadId] = useState<string>(() => {
    const list = listSessions();
    return list.length > 0 ? list[0].id : newThreadId();
  });

  useEffect(() => {
    setSessions(listSessions());
  }, []);

  // 每个会话一个 HttpAgent（threadId 即会话 id）
  const agent = useMemo(
    () =>
      new HttpAgent({
        url: "/api/chat",
        threadId: currentThreadId,
      }),
    [currentThreadId],
  );

  // 会话切换/初始加载后，把本地历史种给 agent（保证后端多轮上下文完整）
  useEffect(() => {
    const history = loadMessages(currentThreadId);
    if (history.length > 0) {
      agent.setMessages(history.map(aguiMessageOf) as never[]);
    }
  }, [agent, currentThreadId]);

  const threadListAdapter = useMemo(
    () => ({
      threadId: currentThreadId,
      onSwitchToNewThread: async () => {
        setCurrentThreadId(newThreadId());
      },
      onSwitchToThread: async (threadId: string) => {
        setCurrentThreadId(threadId);
        return { messages: historyToThreadMessages(loadMessages(threadId)) };
      },
    }),
    [currentThreadId],
  );

  const runtime = useAgUiRuntime({
    agent,
    showThinking: true,
    adapters: { threadList: threadListAdapter },
  });

  // 持久化：thread 消息变化 → localStorage（并刷新会话列表）
  useEffect(() => {
    return runtime.thread.subscribe(() => {
      const { messages } = runtime.thread.getState();
      if (messages.length === 0) return;
      const saved: ChatMessage[] = messages.map((m) => ({
        id: m.id,
        role: m.role === "user" ? "user" : "assistant",
        content: m.content
          .filter(
            (p): p is { type: "text"; text: string } =>
              p.type === "text" && typeof p.text === "string",
          )
          .map((p) => p.text)
          .join("\n\n")
          .trim(),
        createdAt: Date.now(),
      }));
      if (saved.some((m) => m.content)) {
        saveMessages(currentThreadId, saved);
        setSessions(listSessions());
      }
    });
  }, [runtime, currentThreadId]);

  const switchThread = useCallback((id: string) => {
    void threadListAdapter.onSwitchToThread(id);
  }, [threadListAdapter]);

  const newThread = useCallback(() => {
    void threadListAdapter.onSwitchToNewThread();
  }, [threadListAdapter]);

  const removeThread = useCallback(
    (id: string) => {
      deleteSession(id);
      setSessions(listSessions());
      if (id === currentThreadId) {
        void threadListAdapter.onSwitchToNewThread();
      }
    },
    [currentThreadId, threadListAdapter],
  );

  return (
    <ChatRuntimeContext.Provider
      value={{ sessions, currentThreadId, switchThread, newThread, removeThread }}
    >
      <AssistantRuntimeProvider runtime={runtime}>{children}</AssistantRuntimeProvider>
    </ChatRuntimeContext.Provider>
  );
}
