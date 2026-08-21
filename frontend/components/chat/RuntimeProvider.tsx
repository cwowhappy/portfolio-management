"use client";

import type { Message } from "@ag-ui/client";
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
  createConversation,
  deleteConversation,
  listConversations,
  newThreadId,
  saveMessages,
  type ConversationMeta,
} from "@/lib/conversations";
import type { ChatMessage } from "@/lib/types";

export const AGENT_ID = "invest";

// ———— 会话上下文（Sidebar / ThreadArea 读取） ————

interface ChatRuntimeContextValue {
  sessions: ConversationMeta[];
  currentThreadId: string;
  running: boolean;
  newThread: () => Promise<void>;
  switchThread: (threadId: string) => void;
  deleteThread: (threadId: string) => Promise<void>;
  persistMessages: (threadId: string, msgs: ChatMessage[]) => Promise<void>;
  setRunning: (running: boolean) => void;
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

/**
 * AG-UI Message → 本地历史（ChatMessage）。
 * 注意（有意为之）：仅持久化 user/assistant 的纯文本，丢弃 toolCalls 与 reasoning。
 * 依据 ADR-0004 前端只保留精简历史以控制体积；代价是跨会话重灌后多轮上下文不含工具调用轨迹。
 * 若后续需要更强的多轮工具上下文，可扩展 ChatMessage 存 toolCalls 并在 historyToAgentMessages 回放。
 */
export function agentMessagesToHistory(
  messages: Message[],
  existing: ChatMessage[] = [],
): ChatMessage[] {
  const prevCreatedAt = new Map(existing.map((m) => [m.id, m.createdAt]));
  const out: ChatMessage[] = [];
  for (const m of messages) {
    if (m.role !== "user" && m.role !== "assistant") continue;
    const content = typeof m.content === "string" ? m.content.trim() : "";
    if (!content) continue;
    out.push({
      id: m.id,
      role: m.role,
      content,
      createdAt: prevCreatedAt.get(m.id) ?? Date.now(),
    });
  }
  return out;
}

// ———— Provider ————

export function RuntimeProvider({ children }: { children: ReactNode }) {
  // 服务端与客户端初始状态保持一致（空），避免 hydration 不匹配；
  // 真实会话在客户端挂载后（useEffect）才从服务端拉取。
  const [sessions, setSessions] = useState<ConversationMeta[]>([]);
  const [currentThreadId, setCurrentThreadId] = useState<string>("");
  const [running, setRunning] = useState(false);
  const [ready, setReady] = useState(false);

  // 挂载：拉取会话列表；为空则创建首个会话。后端不可达时回退为本地空线程，避免整页白屏。
  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const list = await listConversations();
        if (cancelled) return;
        if (list.length > 0) {
          setSessions(list);
          setCurrentThreadId(list[0].id);
        } else {
          const id = newThreadId();
          await createConversation(id);
          if (cancelled) return;
          setSessions([{ id, title: "新会话", updatedAt: Date.now() }]);
          setCurrentThreadId(id);
        }
      } catch (e) {
        if (cancelled) return;
        console.error("[RuntimeProvider] 初始化会话失败", e);
        setCurrentThreadId(newThreadId());
      } finally {
        if (!cancelled) setReady(true);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  const refresh = useCallback(async () => {
    setSessions(await listConversations());
  }, []);

  // 运行中禁止切换/新建线程：共享 agent 的流式输出仍在追加，切换会把 A 线程内容串写进 B 线程
  const newThread = useCallback(async () => {
    if (running) return;
    const id = newThreadId();
    try {
      await createConversation(id);
      setCurrentThreadId(id);
      await refresh();
    } catch (e) {
      // 创建失败也切换到本地线程，避免悬空操作；后续持久化会记录失败日志
      console.error("[RuntimeProvider] 新建会话失败", e);
      setCurrentThreadId(id);
    }
  }, [running, refresh]);

  const switchThread = useCallback(
    (id: string) => {
      if (running) return;
      setCurrentThreadId(id);
    },
    [running],
  );

  const deleteThread = useCallback(
    async (id: string) => {
      try {
        await deleteConversation(id);
        await refresh();
        // 删除的是当前线程时，切换到一个真实存在的会话（创建新线程），避免悬空 threadId
        if (id === currentThreadId) await newThread();
      } catch (e) {
        console.error("[RuntimeProvider] 删除会话失败", id, e);
      }
    },
    [currentThreadId, newThread, refresh],
  );

  const persistMessages = useCallback(
    async (threadId: string, msgs: ChatMessage[]) => {
      try {
        await saveMessages(threadId, msgs);
        await refresh();
      } catch (e) {
        // 失败只记日志，不打断聊天
        console.error("[RuntimeProvider] 保存会话失败", threadId, e);
      }
    },
    [refresh],
  );

  const value = useMemo(
    () => ({
      sessions,
      currentThreadId,
      running,
      newThread,
      switchThread,
      deleteThread,
      persistMessages,
      setRunning,
    }),
    [sessions, currentThreadId, running, newThread, switchThread, deleteThread, persistMessages],
  );

  // 未解析出 threadId 前不渲染聊天内容（服务端/客户端首帧一致，避免 hydration 错误）
  if (!ready) return null;

  return (
    <ChatRuntimeContext.Provider value={value}>
      {children}
    </ChatRuntimeContext.Provider>
  );
}
