// 会话管理：threadId 标识会话，消息历史存 localStorage（ADR-0004 前端持有历史）。

import type { ChatMessage } from "./types";

export interface SessionMeta {
  id: string;
  title: string;
  updatedAt: number;
}

const SESSIONS_KEY = "invest.sessions";
const messagesKey = (threadId: string) => "invest.messages." + threadId;

function safeParse<T>(raw: string | null, fallback: T): T {
  if (!raw) return fallback;
  try {
    return JSON.parse(raw) as T;
  } catch {
    return fallback;
  }
}

export function newThreadId(): string {
  if (typeof crypto !== "undefined" && crypto.randomUUID) return crypto.randomUUID();
  return "t-" + Date.now() + "-" + Math.random().toString(36).slice(2, 10);
}

export function listSessions(): SessionMeta[] {
  if (typeof window === "undefined") return [];
  return safeParse<SessionMeta[]>(localStorage.getItem(SESSIONS_KEY), []).sort(
    (a, b) => b.updatedAt - a.updatedAt,
  );
}

export function loadMessages(threadId: string): ChatMessage[] {
  if (typeof window === "undefined") return [];
  return safeParse<ChatMessage[]>(localStorage.getItem(messagesKey(threadId)), []);
}

export function saveMessages(threadId: string, messages: ChatMessage[]): void {
  if (typeof window === "undefined") return;
  localStorage.setItem(messagesKey(threadId), JSON.stringify(messages));
  touchSession(threadId, messages);
}

function touchSession(threadId: string, messages: ChatMessage[]): void {
  const sessions = listSessions();
  const title = messages.find((m) => m.role === "user")?.content.slice(0, 24) ?? "新会话";
  const idx = sessions.findIndex((s) => s.id === threadId);
  const meta: SessionMeta = { id: threadId, title, updatedAt: Date.now() };
  if (idx >= 0) {
    sessions[idx] = { ...sessions[idx], ...meta };
  } else {
    sessions.unshift(meta);
  }
  localStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions.slice(0, 50)));
}

export function deleteSession(threadId: string): void {
  if (typeof window === "undefined") return;
  localStorage.removeItem(messagesKey(threadId));
  const sessions = listSessions().filter((s) => s.id !== threadId);
  localStorage.setItem(SESSIONS_KEY, JSON.stringify(sessions));
}
