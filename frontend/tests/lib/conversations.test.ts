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
    it("发送 PUT /messages，body 仅含 id/role/content/createdAt", async () => {
      const api = installConversationsApi();
      await saveMessages("t1", [msg("user", "你好", "u1"), msg("assistant", "答复", "a1")]);
      expect(api.lastPutBody()).toEqual([
        { id: "u1", role: "user", content: "你好", createdAt: 1_700_000_000_000 },
        { id: "a1", role: "assistant", content: "答复", createdAt: 1_700_000_000_000 },
      ]);
      const putCall = api.fetchMock.mock.calls.find(([, init]) => init?.method === "PUT");
      expect(String(putCall?.[0])).toBe("/api/conversations/t1/messages");
    });
  });

  describe("loadMessages", () => {
    it("zod 校验通过并返回消息", async () => {
      installConversationsApi({
        messages: { t1: [msg("user", "历史问题", "m1")] },
      });
      const msgs = await loadMessages("t1");
      expect(msgs).toEqual([msg("user", "历史问题", "m1")]);
    });

    it("响应字段不合法时抛出异常", async () => {
      const api = installConversationsApi();
      api.state.messages.set("t1", [
        { id: "m1", role: "system", content: "非法", createdAt: 1 },
      ] as never);
      await expect(loadMessages("t1")).rejects.toThrow();
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
