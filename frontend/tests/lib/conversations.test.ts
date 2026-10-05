import { afterEach, describe, expect, it, vi } from "vitest";
import {
  createConversation,
  deleteConversation,
  listConversations,
  loadMessages,
  newThreadId,
  saveMessages,
} from "@/lib/conversations";
import { installConversationsApi } from "@/tests/mockConversationsApi";
import type { ChatMessage } from "@/lib/types";

function msg(role: "user" | "assistant", content: string, id = "m1"): ChatMessage {
  return { id, role, content, createdAt: 1_700_000_000_000 };
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("会话客户端（lib/conversations）", () => {
  describe("newThreadId", () => {
    it("优先使用 crypto.randomUUID", () => {
      expect(newThreadId()).toMatch(/^[0-9a-f]{8}-[0-9a-f-]{27}$/);
    });

    it("没有 randomUUID 时回退到时间戳方案", () => {
      const original = globalThis.crypto;
      vi.stubGlobal("crypto", {});
      try {
        expect(newThreadId()).toMatch(/^t-\d+-[a-z0-9]{8}$/);
      } finally {
        vi.stubGlobal("crypto", original);
      }
    });
  });

  describe("listConversations", () => {
    it("返回服务端会话列表（zod 校验通过）", async () => {
      installConversationsApi({
        list: [
          { id: "t1", title: "会话一", updatedAt: 200 },
          { id: "t2", title: "会话二", updatedAt: 100 },
        ],
      });
      const list = await listConversations();
      expect(list.map((c) => c.id)).toEqual(["t1", "t2"]);
    });
  });

  describe("createConversation", () => {
    it("POST {id} 并返回会话元数据", async () => {
      const api = installConversationsApi();
      const meta = await createConversation("new-1");
      expect(meta.id).toBe("new-1");
      expect(meta.title).toBe("新会话");
      expect(api.state.list[0].id).toBe("new-1");
    });
  });

  describe("saveMessages", () => {
    it("发送 PUT /messages，body 仅含 id/role/content/createdAt，返回服务端 updatedAt", async () => {
      const api = installConversationsApi();
      const result = await saveMessages("t1", [msg("user", "你好", "u1"), msg("assistant", "答复", "a1")]);
      expect(api.lastPutBody()).toEqual([
        { id: "u1", role: "user", content: "你好", createdAt: 1_700_000_000_000 },
        { id: "a1", role: "assistant", content: "答复", createdAt: 1_700_000_000_000 },
      ]);
      const putCall = api.fetchMock.mock.calls.find(([, init]) => init?.method === "PUT");
      expect(String(putCall?.[0])).toBe("/api/conversations/t1/messages");
      // B6 契约：PUT 200 返回 {updatedAt}
      expect(result.updatedAt).toBe("2026-10-04T00:00:01.000Z");
    });

    it("携带 ifMatch 时发送 If-Match 请求头（乐观校验）", async () => {
      const api = installConversationsApi({ messages: { t1: [] } });
      await saveMessages("t1", [msg("user", "你好", "u1")], {
        ifMatch: "2026-10-04T00:00:00.000Z",
      });
      const putCall = api.fetchMock.mock.calls.find(([, init]) => init?.method === "PUT");
      expect(new Headers(putCall?.[1]?.headers).get("If-Match")).toBe("2026-10-04T00:00:00.000Z");
    });

    it("If-Match 与服务端 updatedAt 不一致时 409，错误携带状态码", async () => {
      installConversationsApi({ messages: { t1: [] } });
      await expect(
        saveMessages("t1", [msg("user", "你好", "u1")], { ifMatch: "stale-value" }),
      ).rejects.toMatchObject({ status: 409 });
    });

    // MS-29 F4：payload 仅在 assistant 携带非空 JSON 文本时入 PUT body；
    // user（恒不带）与 null/缺省（B8 降级回带形态）均省略键。
    it("PUT body 携带 assistant payload；user 与 null/缺省省略 payload 键（F4）", async () => {
      const api = installConversationsApi();
      await saveMessages("t1", [
        msg("user", "你好", "u1"),
        { ...msg("assistant", "答复", "a1"), payload: '{"v":1}' },
        { ...msg("assistant", "降级回带", "a2"), payload: null },
      ]);
      const body = api.lastPutBody()!;
      expect(body[0]).toEqual({
        id: "u1",
        role: "user",
        content: "你好",
        createdAt: 1_700_000_000_000,
      });
      expect(Object.prototype.hasOwnProperty.call(body[0], "payload")).toBe(false);
      expect(body[1].payload).toBe('{"v":1}');
      expect(Object.prototype.hasOwnProperty.call(body[2], "payload")).toBe(false);
    });
  });

  describe("loadMessages", () => {
    it("zod 校验通过并返回 {updatedAt, messages} 包装（B6 契约）", async () => {
      installConversationsApi({
        messages: { t1: [msg("user", "历史问题", "m1")] },
      });
      const view = await loadMessages("t1");
      expect(view.messages).toEqual([msg("user", "历史问题", "m1")]);
      expect(view.updatedAt).toBe("2026-10-04T00:00:00.000Z");
    });

    it("响应字段不合法时抛出异常", async () => {
      const api = installConversationsApi();
      api.state.messages.set("t1", [
        { id: "m1", role: "system", content: "非法", createdAt: 1 },
      ] as never);
      await expect(loadMessages("t1")).rejects.toThrow();
    });

    // MS-29 F4 zod 往返：B8 契约三形态——assistant JSON 文本 / user 回带 null / 旧记录缺键
    it("zod 往返：assistant payload JSON 文本 / user payload null（B8 回带）/ 缺省键（F4）", async () => {
      installConversationsApi({
        messages: {
          t1: [
            { id: "u1", role: "user", content: "问", createdAt: 1, payload: null },
            { id: "a1", role: "assistant", content: "答", createdAt: 2, payload: '{"v":1}' },
            { id: "a2", role: "assistant", content: "答二", createdAt: 3 },
          ],
        },
      });
      const view = await loadMessages("t1");
      expect(view.messages[0].payload).toBeNull();
      expect(view.messages[1].payload).toBe('{"v":1}');
      expect(view.messages[2].payload).toBeUndefined();
    });
  });

  describe("deleteConversation", () => {
    it("DELETE 会话并清空消息", async () => {
      const api = installConversationsApi({
        list: [{ id: "t1", title: "一", updatedAt: 1 }],
        messages: { t1: [msg("user", "问题")] },
      });
      await deleteConversation("t1");
      expect(api.state.list).toEqual([]);
      expect(api.state.messages.has("t1")).toBe(false);
    });
  });

  describe("非 2xx 响应", () => {
    it("抛出后端 message", async () => {
      vi.stubGlobal(
        "fetch",
        vi.fn(async () => new Response(JSON.stringify({ message: "会话不存在" }), { status: 404 })),
      );
      await expect(loadMessages("missing")).rejects.toThrow("会话不存在");
    });

    it("无 message 时抛出默认文案", async () => {
      vi.stubGlobal("fetch", vi.fn(async () => new Response("oops", { status: 500 })));
      await expect(listConversations()).rejects.toThrow("请求失败");
    });
  });
});
