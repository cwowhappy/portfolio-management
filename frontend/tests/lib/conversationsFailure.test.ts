import { afterEach, describe, expect, it, vi } from "vitest";
import { errorStatus, loadMessages, saveMessages } from "@/lib/conversations";
import type { ChatMessage } from "@/lib/types";

afterEach(() => {
  vi.unstubAllGlobals();
});

// P3-⑦：keepalive PUT 传输层失败（断网 / keepalive 体积超限被浏览器拒绝）此前裸抛 TypeError，
// 调用方 errorStatus 无法分类。现在与 HTTP 错误同口径：包成带 status 的错误（status=0 表传输层）。
describe("会话持久化传输层失败上抛（P3-⑦）", () => {
  it("fetch 传输层拒绝（keepalive 体积超限）→ 上抛带 status=0 的错误，保留原始 message", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Request body exceeds the keepalive limit");
      }),
    );
    const msgs: ChatMessage[] = [{ id: "u1", role: "user", content: "你好", createdAt: 1 }];
    await expect(saveMessages("t1", msgs, { keepalive: true })).rejects.toMatchObject({
      status: 0,
      message: "Request body exceeds the keepalive limit",
    });
  });

  it("errorStatus 把传输层失败分类为 0（与 409 等 HTTP 错误同口径可分类）", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw new TypeError("Failed to fetch");
      }),
    );
    let captured: unknown;
    await loadMessages("t1").catch((e: unknown) => {
      captured = e;
    });
    expect(errorStatus(captured)).toBe(0);
  });

  it("非 Error 拒绝值回退默认文案，仍带 status=0", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () => {
        throw "boom";
      }),
    );
    await expect(loadMessages("t1")).rejects.toMatchObject({
      status: 0,
      message: "网络请求失败",
    });
  });
});
