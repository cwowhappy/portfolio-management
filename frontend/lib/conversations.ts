// 会话持久化客户端（经 /api/conversations 反代），响应用 zod 在边界校验。
// 替代原 localStorage 方案（lib/sessions.ts），threadId 即 conversation.id。
// P3 收敛：status 承载 / keepalive / If-Match 等传输形态统一经 lib/http.ts 的 RequestOptions
// 注入，本模块不再持有私有 request；错误状态码读取仍走本模块 errorStatus。

import { z } from "zod";
import { request } from "./http";
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
  // MS-29 F4：assistant 携带信任 payload（JSON 文本）；B8 契约 GET 回带 user 消息为 null，
  // 旧记录/降级形态缺键——nullish 三态全容纳
  payload: z.string().nullish(),
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

/** 读取 request() 抛出错误上的 HTTP 状态码；非本层错误返回 null。 */
export function errorStatus(e: unknown): number | null {
  if (typeof e === "object" && e !== null) {
    const s = (e as { status?: unknown }).status;
    if (typeof s === "number") return s;
  }
  return null;
}

// withStatus 口径：非 2xx 错误挂 HTTP status（409 乐观锁分类）、传输层失败挂 0（P3-⑦）
const withStatus = { withStatus: true } as const;

export const listConversations = () =>
  request("/api/conversations", "GET", undefined, z.array(MetaSchema), withStatus);
export const createConversation = (id: string) =>
  request("/api/conversations", "POST", { id }, MetaSchema, withStatus);
export const loadMessages = (threadId: string): Promise<MessagesView> =>
  request(`/api/conversations/${threadId}/messages`, "GET", undefined, MessagesViewSchema, withStatus);
export const saveMessages = (
  threadId: string,
  msgs: ChatMessage[],
  opts?: { keepalive?: boolean; ifMatch?: string },
) =>
  request(
    `/api/conversations/${threadId}/messages`,
    "PUT",
    msgs.map((m) => ({
      id: m.id,
      role: m.role,
      content: m.content,
      createdAt: m.createdAt,
      // MS-29 F4：payload 仅携带非空 JSON 文本（user 恒不带；null/缺省为 B8 回带形态，
      // 后端守卫口径本就归一 null，省键等价且少一条降级 WARN）
      ...(typeof m.payload === "string" && m.payload !== "" ? { payload: m.payload } : {}),
    })),
    SaveResultSchema,
    {
      ...withStatus,
      init: {
        ...(opts?.keepalive ? { keepalive: true } : {}),
        // B6 乐观校验：携带服务端 updatedAt 作为 If-Match，冲突时后端返回 409
        ...(opts?.ifMatch ? { headers: { "If-Match": opts.ifMatch } } : {}),
      },
    },
  );
export const deleteConversation = (threadId: string) =>
  request(`/api/conversations/${threadId}`, "DELETE", undefined, z.void(), withStatus);
