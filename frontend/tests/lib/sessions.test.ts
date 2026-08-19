import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  deleteSession,
  listSessions,
  loadMessages,
  newThreadId,
  saveMessages,
  type SessionMeta,
} from "@/lib/sessions";
import type { ChatMessage } from "@/lib/types";

function msg(role: "user" | "assistant", content: string, id = "m1"): ChatMessage {
  return { id, role, content, createdAt: 1_700_000_000_000 };
}

describe("会话管理（lib/sessions）", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

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

  describe("listSessions", () => {
    it("无数据时返回空数组", () => {
      expect(listSessions()).toEqual([]);
    });

    it("按 updatedAt 降序排序", () => {
      const sessions: SessionMeta[] = [
        { id: "a", title: "旧", updatedAt: 100 },
        { id: "b", title: "新", updatedAt: 300 },
        { id: "c", title: "中", updatedAt: 200 },
      ];
      localStorage.setItem("invest.sessions", JSON.stringify(sessions));
      expect(listSessions().map((s) => s.id)).toEqual(["b", "c", "a"]);
    });

    it("数据损坏时返回空数组", () => {
      localStorage.setItem("invest.sessions", "{not-json");
      expect(listSessions()).toEqual([]);
    });

    it("SSR 环境（无 window）返回空数组", () => {
      const original = globalThis.window;
      vi.stubGlobal("window", undefined);
      try {
        expect(listSessions()).toEqual([]);
      } finally {
        vi.stubGlobal("window", original);
      }
    });
  });

  describe("loadMessages", () => {
    it("读取并解析消息历史", () => {
      localStorage.setItem("invest.messages.t1", JSON.stringify([msg("user", "你好")]));
      expect(loadMessages("t1")).toEqual([msg("user", "你好")]);
    });

    it("无历史时返回空数组", () => {
      expect(loadMessages("missing")).toEqual([]);
    });

    it("损坏数据返回空数组", () => {
      localStorage.setItem("invest.messages.t1", "oops");
      expect(loadMessages("t1")).toEqual([]);
    });
  });

  describe("saveMessages", () => {
    it("写入消息并为新会话建立标题（取首条用户消息前 24 字）", () => {
      vi.setSystemTime(new Date(1_800_000_000_000));
      saveMessages("t1", [msg("user", "帮我看看贵州茅台最近的走势和估值情况如何"), msg("assistant", "好的")]);
      expect(loadMessages("t1")).toHaveLength(2);
      const sessions = listSessions();
      expect(sessions).toHaveLength(1);
      expect(sessions[0].id).toBe("t1");
      expect(sessions[0].title).toBe("帮我看看贵州茅台最近的走势和估值情况如何".slice(0, 24));
      expect(sessions[0].updatedAt).toBe(1_800_000_000_000);
    });

    it("更新已有会话的标题与时间", () => {
      vi.setSystemTime(new Date(1_800_000_000_000));
      saveMessages("t1", [msg("user", "第一个问题")]);
      vi.setSystemTime(new Date(1_800_000_100_000));
      saveMessages("t1", [msg("user", "第二个问题")]);
      const sessions = listSessions();
      expect(sessions).toHaveLength(1);
      expect(sessions[0].title).toBe("第二个问题");
      expect(sessions[0].updatedAt).toBe(1_800_000_100_000);
    });

    it("没有用户消息时标题回退为“新会话”", () => {
      saveMessages("t1", [msg("assistant", "只有助手消息")]);
      expect(listSessions()[0].title).toBe("新会话");
    });

    it("会话列表最多保留 50 条", () => {
      const many: SessionMeta[] = Array.from({ length: 60 }, (_, i) => ({
        id: "old-" + i,
        title: "旧会话 " + i,
        updatedAt: i,
      }));
      localStorage.setItem("invest.sessions", JSON.stringify(many));
      saveMessages("new-thread", [msg("user", "新会话")]);
      expect(listSessions()).toHaveLength(50);
    });
  });

  describe("deleteSession", () => {
    it("删除消息并移出会话列表", () => {
      saveMessages("t1", [msg("user", "问题一")]);
      saveMessages("t2", [msg("user", "问题二")]);
      deleteSession("t1");
      expect(loadMessages("t1")).toEqual([]);
      expect(listSessions().map((s) => s.id)).toEqual(["t2"]);
    });
  });
});
