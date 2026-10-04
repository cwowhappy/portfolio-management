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
// B6 契约：GET /messages 返回 {updatedAt(ISO 字符串), messages}；PUT 200 返回 {updatedAt}
const MessagesViewSchema = z.object({ updatedAt: z.string(), messages: z.array(MsgSchema) });
const SaveResultSchema = z.object({ updatedAt: z.string() });

export interface MessagesView {
  updatedAt: string;
  messages: ChatMessage[];
}

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
    // 携带 HTTP 状态码抛出（如 409 并发冲突），供调用方按错误分类处理
    throw Object.assign(new Error(message), { status: res.status });
  }
  if (res.status === 204) return undefined as T;
  return schema.parse(await res.json());
}

/** 读取 request() 抛出错误上的 HTTP 状态码；非本层错误返回 null。 */
export function errorStatus(e: unknown): number | null {
  if (typeof e === "object" && e !== null) {
    const s = (e as { status?: unknown }).status;
    if (typeof s === "number") return s;
  }
  return null;
}

export const listConversations = () => request("/api/conversations", z.array(MetaSchema));
export const createConversation = (id: string) =>
  request("/api/conversations", MetaSchema, { method: "POST", body: JSON.stringify({ id }) });
export const loadMessages = (threadId: string): Promise<MessagesView> =>
  request(`/api/conversations/${threadId}/messages`, MessagesViewSchema);
export const saveMessages = (
  threadId: string,
  msgs: ChatMessage[],
  opts?: { keepalive?: boolean; ifMatch?: string },
) =>
  request(`/api/conversations/${threadId}/messages`, SaveResultSchema, {
    method: "PUT",
    ...(opts?.keepalive ? { keepalive: true } : {}),
    // B6 乐观校验：携带服务端 updatedAt 作为 If-Match，冲突时后端返回 409
    ...(opts?.ifMatch ? { headers: { "If-Match": opts.ifMatch } } : {}),
    body: JSON.stringify(
      msgs.map((m) => ({ id: m.id, role: m.role, content: m.content, createdAt: m.createdAt })),
    ),
  });
export const deleteConversation = (threadId: string) =>
  request(`/api/conversations/${threadId}`, z.void(), { method: "DELETE" });
