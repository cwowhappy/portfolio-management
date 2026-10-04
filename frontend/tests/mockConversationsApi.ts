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
  /** PUT /messages 先返回 N 次 409（并发冲突，不改动内存态），第 N+1 次起正常整体替换 */
  putConflictTimes?: number;
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
 * - GET    /api/conversations/:id/messages → {updatedAt, messages}（B6 契约：updatedAt ISO 字符串）
 * - PUT    /api/conversations/:id/messages → 整体替换，返回 200 {updatedAt}；携带 If-Match 时校验服务端
 *                                            updatedAt，不一致返回 409（模拟后端乐观校验）
 * - DELETE /api/conversations/:id          → 删除会话与消息，返回 204
 */
export function installConversationsApi(initial: InstallOptions = {}): ConversationsApiHarness {
  const state = {
    list: [...(initial.list ?? [])],
    messages: new Map<string, MockMsg[]>(Object.entries(initial.messages ?? {})),
  };
  // B6：每会话的服务端 updatedAt（ISO 字符串），GET 回显、PUT 携带 If-Match 时校验
  const convUpdatedAt = new Map<string, string>();
  const INITIAL_UPDATED_AT = "2026-10-04T00:00:00.000Z";
  const currentUpdatedAt = (id: string): string => {
    if (!convUpdatedAt.has(id)) convUpdatedAt.set(id, INITIAL_UPDATED_AT);
    return convUpdatedAt.get(id)!;
  };
  let putConflictsLeft = initial.putConflictTimes ?? 0;

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

    if (url === "/api/conversations" && method === "GET") {
      // 模拟后端 ORDER BY updated_at DESC
      return json(200, [...state.list].sort((a, b) => b.updatedAt - a.updatedAt));
    }
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
      if (method === "GET")
        return json(200, { updatedAt: currentUpdatedAt(id), messages: state.messages.get(id) ?? [] });
      if (method === "PUT") {
        // B6 并发冲突模拟：前 N 次返回 409 且不改内存态；此后若携带 If-Match 且与服务端
        // updatedAt 不一致也返回 409（对齐后端「先校验冲突、后执行替换」语义）
        const ifMatch = new Headers(init?.headers).get("If-Match");
        if (putConflictsLeft > 0 || (ifMatch !== null && ifMatch !== currentUpdatedAt(id))) {
          putConflictsLeft = Math.max(0, putConflictsLeft - 1);
          return json(409, { message: "会话已被其他窗口修改" });
        }
        const msgs = JSON.parse(bodyText || "[]") as MockMsg[];
        state.messages.set(id, msgs);
        convUpdatedAt.set(id, "2026-10-04T00:00:01.000Z");
        // 模拟后端 touch + renameIfDefault：updatedAt 置为当前；默认标题时取首条用户消息前 24 字
        const firstUser = msgs.find((m) => m.role === "user")?.content.trim() ?? "";
        state.list = state.list.map((c) =>
          c.id === id
            ? {
                ...c,
                updatedAt: Date.now(),
                title: c.title === "新会话" && firstUser ? firstUser.slice(0, 24) : c.title,
              }
            : c,
        );
        return json(200, { updatedAt: currentUpdatedAt(id) });
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
