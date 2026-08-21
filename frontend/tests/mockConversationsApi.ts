import { vi } from "vitest";

export interface MockConvMeta {
  id: string;
  title: string;
  updatedAt: number;
}

export interface MockMsg {
  id: string;
  role: "user" | "assistant";
  content: string;
  createdAt: number;
}

export interface InstallOptions {
  list?: MockConvMeta[];
  messages?: Record<string, MockMsg[]>;
}

export interface ConversationsApiHarness {
  /** 内存态：会话列表（最新在前）与各会话消息 */
  state: { list: MockConvMeta[]; messages: Map<string, MockMsg[]> };
  fetchMock: ReturnType<typeof vi.fn>;
  /** 最近一次 PUT /messages 的请求体；没有则返回 null */
  lastPutBody: () => MockMsg[] | null;
}

/**
 * 安装一个模拟 /api/conversations* 的 fetch（含运行时内存态）。
 * - GET    /api/conversations              → state.list
 * - POST   /api/conversations  {id}        → 创建会话（title=新会话，updatedAt=now），返回 201
 * - GET    /api/conversations/:id/messages → state.messages
 * - PUT    /api/conversations/:id/messages → 整体替换，返回 204
 * - DELETE /api/conversations/:id          → 删除会话与消息，返回 204
 */
export function installConversationsApi(initial: InstallOptions = {}): ConversationsApiHarness {
  const state = {
    list: [...(initial.list ?? [])],
    messages: new Map<string, MockMsg[]>(Object.entries(initial.messages ?? {})),
  };

  const json = (status: number, body?: unknown) =>
    new Response(body === undefined ? null : JSON.stringify(body), {
      status,
      headers: { "Content-Type": "application/json" },
    });
  const noContent = () => new Response(null, { status: 204 });

  const fetchMock = vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input);
    const method = init?.method ?? "GET";
    const bodyText = typeof init?.body === "string" ? init.body : "";

    if (url === "/api/conversations" && method === "GET") return json(200, state.list);
    if (url === "/api/conversations" && method === "POST") {
      const { id } = JSON.parse(bodyText || "{}") as { id?: string };
      if (!id) return json(400, { message: "缺少 id" });
      const meta: MockConvMeta = { id, title: "新会话", updatedAt: Date.now() };
      state.list.unshift(meta);
      state.messages.set(id, []);
      return json(201, meta);
    }
    const msgMatch = url.match(/^\/api\/conversations\/([^/]+)\/messages$/);
    if (msgMatch) {
      const id = decodeURIComponent(msgMatch[1]);
      if (method === "GET") return json(200, state.messages.get(id) ?? []);
      if (method === "PUT") {
        state.messages.set(id, JSON.parse(bodyText || "[]") as MockMsg[]);
        return noContent();
      }
    }
    const convMatch = url.match(/^\/api\/conversations\/([^/]+)$/);
    if (convMatch && method === "DELETE") {
      const id = decodeURIComponent(convMatch[1]);
      state.list = state.list.filter((c) => c.id !== id);
      state.messages.delete(id);
      return noContent();
    }
    return json(404, { message: "not found: " + method + " " + url });
  });

  vi.stubGlobal("fetch", fetchMock);

  return {
    state,
    fetchMock,
    lastPutBody: (): MockMsg[] | null => {
      const putCalls = fetchMock.mock.calls
        .map(([input, init]) => ({ url: String(input), init }))
        .filter((c) => c.init?.method === "PUT" && /\/messages$/.test(c.url));
      const last = putCalls[putCalls.length - 1];
      if (!last) return null;
      return JSON.parse(String(last.init?.body)) as MockMsg[];
    },
  };
}
