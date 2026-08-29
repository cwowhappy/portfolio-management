// 会话持久化客户端（经 /api/conversations 反代），响应用 zod 在边界校验。
// 替代原 localStorage 方案（lib/sessions.ts），threadId 即 conversation.id。

import { z } from "zod";
import type { ChatMessage } from "./types";

export interface ConversationMeta {
  id: string;
  title: string;
  updatedAt: number;
}

const MetaSchema = z.object({ id: z.string(), title: z.string(), updatedAt: z.number() });
const MsgSchema = z.object({
  id: z.string(),
  role: z.enum(["user", "assistant"]),
  content: z.string(),
  createdAt: z.number(),
});

export function newThreadId(): string {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
  return "t-" + Date.now() + "-" + Math.random().toString(36).slice(2, 10);
}

async function request<T>(path: string, schema: z.ZodType<T>, init?: RequestInit): Promise<T> {
  const res = await fetch(path, { cache: "no-store", ...init });
  if (!res.ok) {
    let message = "请求失败";
    try {
      const b = await res.json();
      if (b?.message) message = b.message;
    } catch {
      /* ignore */
    }
    throw new Error(message);
  }
  if (res.status === 204) return undefined as T;
  return schema.parse(await res.json());
}

export const listConversations = () => request("/api/conversations", z.array(MetaSchema));
export const createConversation = (id: string) =>
  request("/api/conversations", MetaSchema, { method: "POST", body: JSON.stringify({ id }) });
export const loadMessages = (threadId: string) =>
  request(`/api/conversations/${threadId}/messages`, z.array(MsgSchema));
export const saveMessages = (threadId: string, msgs: ChatMessage[], opts?: { keepalive?: boolean }) =>
  request(`/api/conversations/${threadId}/messages`, z.void(), {
    method: "PUT",
    ...(opts?.keepalive ? { keepalive: true } : {}),
    body: JSON.stringify(
      msgs.map((m) => ({ id: m.id, role: m.role, content: m.content, createdAt: m.createdAt })),
    ),
  });
export const deleteConversation = (threadId: string) =>
  request(`/api/conversations/${threadId}`, z.void(), { method: "DELETE" });
