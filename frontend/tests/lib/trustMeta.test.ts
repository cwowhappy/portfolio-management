import { describe, expect, it, vi } from "vitest";
import type { Message } from "@ag-ui/client";
import {
  TRUST_ANCHORS_EVENT,
  TRUST_CORRECTION_EVENT,
  TrustPayloadSchema,
  createTrustStore,
  handleTrustCustomEvent,
  nthIndexOf,
  replaceSnippetInMessages,
} from "@/lib/trustMeta";
import type { TrustPayload } from "@/lib/trustMeta";

// ———— MS-29 F1：信任事件（trust.anchors / trust.correction）接入单元 ————

/** payload v1 夹具（后端 TrustTurnReport#toPayload 同构：无 null 用缺键）。 */
function anchorsPayload(overrides: Record<string, unknown> = {}): TrustPayload {
  return {
    v: 1,
    anchors: [
      {
        snippet: "1741 亿",
        occ: 1,
        state: "verified",
        tool: "fetchStockQuote",
        args: { code: "600519" },
        asOf: "2026-10-01",
        asOfKind: "data",
        raw: "1741.44",
      },
      { snippet: "25 倍", occ: 2, state: "unverified" },
    ],
    stats: { verified: 1, sourced: 0, unverified: 1 },
    ...overrides,
  } as TrustPayload;
}

function anchorsValue(messageId: string, overrides: Record<string, unknown> = {}) {
  return { messageId, payload: anchorsPayload(overrides) };
}

function correctionValue(messageId: string, snippet = "1741 亿", occ = 1, replacement = "1708 亿") {
  return {
    messageId,
    snippet,
    occ,
    replacement,
    note: `已按工具返回值将 ${snippet} 修正为 ${replacement}`,
  };
}

function assistantMsg(id: string, content: string): Message {
  return { id, role: "assistant", content } as Message;
}

describe("TrustPayloadSchema（宽松解析 + 版本门）", () => {
  it("未知字段 strip：解析产物只含契约键", () => {
    const wire = {
      ...anchorsValue("m1").payload,
      extraTop: "x",
      anchors: [
        { ...anchorsValue("m1").payload.anchors[0], extraAnchor: 1 },
        ...anchorsValue("m1").payload.anchors.slice(1),
      ],
    };
    const parsed = TrustPayloadSchema.parse(wire);
    expect(parsed).not.toHaveProperty("extraTop");
    expect(parsed.anchors[0]).not.toHaveProperty("extraAnchor");
    // 契约字段完整保留
    expect(parsed.anchors[0]).toMatchObject({
      snippet: "1741 亿",
      occ: 1,
      state: "verified",
      tool: "fetchStockQuote",
    });
  });

  it("版本门：v 非 1 解析失败（事件将被忽略）", () => {
    const v2 = { ...anchorsValue("m1").payload, v: 2 };
    expect(TrustPayloadSchema.safeParse(v2).success).toBe(false);
  });

  it("v 缺失 / payload 为 null 解析失败", () => {
    const { v: _v, ...noV } = anchorsValue("m1").payload;
    expect(TrustPayloadSchema.safeParse(noV).success).toBe(false);
    expect(TrustPayloadSchema.safeParse(null).success).toBe(false);
  });

  it("可选半边缺键合法：无 correction/advice/confidence 的最小 payload 通过", () => {
    const parsed = TrustPayloadSchema.parse(anchorsValue("m1").payload);
    expect(parsed.correction).toBeUndefined();
    expect(parsed.advice).toBeUndefined();
    expect(parsed.confidence).toBeUndefined();
  });
});

describe("nthIndexOf（occ 1-based，含非数据性出现）", () => {
  const text = "市盈率 25 倍，市净率 25 倍，又说 25 倍。";

  it("occ=1/2/3 定位各次出现起始位", () => {
    expect(nthIndexOf(text, "25 倍", 1)).toBe(text.indexOf("25 倍"));
    expect(nthIndexOf(text, "25 倍", 2)).toBe(text.indexOf("25 倍", text.indexOf("25 倍") + 1));
    expect(nthIndexOf(text, "25 倍", 3)).toBe(text.lastIndexOf("25 倍"));
  });

  it("越界 / 不存在 / 空 snippet / occ<1 返回 -1", () => {
    expect(nthIndexOf(text, "25 倍", 4)).toBe(-1);
    expect(nthIndexOf(text, "99 倍", 1)).toBe(-1);
    expect(nthIndexOf(text, "", 1)).toBe(-1);
    expect(nthIndexOf(text, "25 倍", 0)).toBe(-1);
  });
});

describe("TrustStore", () => {
  it("applyAnchors 落地 payload；同 messageId 重复 anchors 事件幂等覆盖（后到整体覆盖）", () => {
    const store = createTrustStore();
    store.applyAnchors("m1", anchorsValue("m1").payload);
    expect(store.get("m1")?.stats.verified).toBe(1);

    const second = anchorsValue("m1", { stats: { verified: 2, sourced: 3, unverified: 0 } }).payload;
    store.applyAnchors("m1", second);
    expect(store.get("m1")).toMatchObject({ stats: { verified: 2, sourced: 3, unverified: 0 } });
  });

  it("correction 先于 anchors 到达：注记先落占位，anchors 到达后并入不被覆盖", () => {
    const store = createTrustStore();
    store.applyCorrection("m1", "note-1");
    // 占位：anchors 空、stats 全 0、correction.notes 已有
    expect(store.get("m1")).toMatchObject({
      v: 1,
      anchors: [],
      stats: { verified: 0, sourced: 0, unverified: 0 },
      correction: { notes: ["note-1"] },
    });

    store.applyAnchors("m1", anchorsValue("m1").payload);
    expect(store.get("m1")?.anchors).toHaveLength(2);
    expect(store.get("m1")?.correction).toEqual({ notes: ["note-1"] });
  });

  it("anchors 自带 correction.notes 时与先到注记做并集（保序去重）", () => {
    const store = createTrustStore();
    store.applyCorrection("m1", "note-early");
    store.applyAnchors("m1", anchorsValue("m1", { correction: { notes: ["note-wire", "note-early"] } }).payload);
    expect(store.get("m1")?.correction).toEqual({ notes: ["note-wire", "note-early"] });
  });

  it("applyCorrection 重复注记幂等：不重复追加", () => {
    const store = createTrustStore();
    store.applyCorrection("m1", "note-1");
    store.applyCorrection("m1", "note-1");
    expect(store.get("m1")?.correction).toEqual({ notes: ["note-1"] });
  });

  it("get 返回引用稳定：无变更时两次 get 同引用；变更后换新引用（useSyncExternalStore 契约）", () => {
    const store = createTrustStore();
    store.applyAnchors("m1", anchorsValue("m1").payload);
    const a = store.get("m1");
    store.applyCorrection("m2", "n");
    expect(store.get("m1")).toBe(a);
    store.applyCorrection("m1", "n");
    expect(store.get("m1")).not.toBe(a);
  });

  it("subscribe：变更通知、退订后不再通知", () => {
    const store = createTrustStore();
    const listener = vi.fn();
    const unsubscribe = store.subscribe(listener);
    store.applyAnchors("m1", anchorsValue("m1").payload);
    expect(listener).toHaveBeenCalledTimes(1);
    unsubscribe();
    store.applyCorrection("m1", "n");
    expect(listener).toHaveBeenCalledTimes(1);
  });
});

describe("replaceSnippetInMessages（原位替换）", () => {
  const content = "市盈率 25 倍，市净率 25 倍。";
  const messages: Message[] = [
    { id: "u1", role: "user", content: "看看估值" } as Message,
    assistantMsg("a1", content),
    assistantMsg("a2", "无关消息"),
  ];

  it("替换 occ 第 2 次出现；其余消息引用不变", () => {
    const next = replaceSnippetInMessages(messages, "a1", "25 倍", 2, "31 倍");
    expect(next).not.toBeNull();
    expect(next![1].content).toBe("市盈率 25 倍，市净率 31 倍。");
    expect(next![0]).toBe(messages[0]);
    expect(next![2]).toBe(messages[2]);
  });

  it("occ 越界 / 消息不存在 / 非字符串 content 返回 null（重复事件 no-op 语义）", () => {
    expect(replaceSnippetInMessages(messages, "a1", "25 倍", 3, "x")).toBeNull();
    expect(replaceSnippetInMessages(messages, "nope", "25 倍", 1, "x")).toBeNull();
    const complex = [{ type: "text", text: "hi" }] as unknown as string;
    expect(replaceSnippetInMessages([assistantMsg("a3", complex)], "a3", "hi", 1, "x")).toBeNull();
  });

  it("不改入参数组与消息对象（库管线 dev/test 下深冻结 params.messages）", () => {
    const frozen = messages.map((m) => Object.freeze(m)) as readonly Message[];
    const next = replaceSnippetInMessages(frozen, "a1", "25 倍", 2, "31 倍");
    expect(next).not.toBeNull();
    expect(messages[1].content).toBe(content);
  });
});

describe("handleTrustCustomEvent（事件分派）", () => {
  it("trust.anchors → store 落地，返回 void（不产 messages 变更）", () => {
    const store = createTrustStore();
    const result = handleTrustCustomEvent(
      { name: TRUST_ANCHORS_EVENT, value: anchorsValue("a1") },
      [],
      store,
    );
    expect(result).toBeUndefined();
    expect(store.get("a1")?.stats).toEqual({ verified: 1, sourced: 0, unverified: 1 });
  });

  it("trust.correction → 返回 AgentStateMutation.messages（occ 第 2 次出现替换）+ 注记落 store", () => {
    const store = createTrustStore();
    const messages = [assistantMsg("a1", "营收 1741 亿（他处 1741 亿）。")];
    const result = handleTrustCustomEvent(
      { name: TRUST_CORRECTION_EVENT, value: correctionValue("a1", "1741 亿", 2, "1708 亿") },
      messages,
      store,
    );
    expect(result).toEqual({
      messages: [expect.objectContaining({ id: "a1", content: "营收 1741 亿（他处 1708 亿）。" })],
    });
    expect(store.get("a1")?.correction?.notes).toHaveLength(1);
  });

  it("重复 correction 事件（替换目标已不存在）：返回 void，注记不重复", () => {
    const store = createTrustStore();
    const messages = [assistantMsg("a1", "营收 1741 亿。")];
    const first = handleTrustCustomEvent(
      { name: TRUST_CORRECTION_EVENT, value: correctionValue("a1") },
      messages,
      store,
    );
    expect(first?.messages?.[0].content).toBe("营收 1708 亿。");
    const second = handleTrustCustomEvent(
      { name: TRUST_CORRECTION_EVENT, value: correctionValue("a1") },
      first!.messages!,
      store,
    );
    expect(second).toBeUndefined();
    expect(store.get("a1")?.correction?.notes).toHaveLength(1);
  });

  it("消息不存在时 correction 不产 messages 变更，但注记仍落 store（供渲染注记）", () => {
    const store = createTrustStore();
    const result = handleTrustCustomEvent(
      { name: TRUST_CORRECTION_EVENT, value: correctionValue("ghost") },
      [assistantMsg("a1", "营收 1741 亿。")],
      store,
    );
    expect(result).toBeUndefined();
    expect(store.get("ghost")?.correction?.notes).toHaveLength(1);
  });

  it("未知 name 忽略；value 缺字段/为 null 忽略；event 为 null 忽略——均不炸", () => {
    const store = createTrustStore();
    expect(handleTrustCustomEvent({ name: "other.event", value: { any: 1 } }, [], store)).toBeUndefined();
    expect(
      handleTrustCustomEvent({ name: TRUST_ANCHORS_EVENT, value: { messageId: "m1", payload: null } }, [], store),
    ).toBeUndefined();
    expect(
      handleTrustCustomEvent({ name: TRUST_CORRECTION_EVENT, value: { messageId: "m1" } }, [], store),
    ).toBeUndefined();
    expect(handleTrustCustomEvent(null, [], store)).toBeUndefined();
    expect(handleTrustCustomEvent({ name: TRUST_ANCHORS_EVENT, value: anchorsValue("m1", { v: 2 }) }, [], store)).toBeUndefined();
    expect(store.get("m1")).toBeUndefined();
  });
});
