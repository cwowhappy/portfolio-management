import { describe, expect, it, vi } from "vitest";
import type { Message } from "@ag-ui/client";
import {
  TRUST_ANCHORS_EVENT,
  TRUST_CORRECTION_EVENT,
  TrustPayloadSchema,
  createTrustStore,
  handleTrustCustomEvent,
  nthIndexOf,
  parseTrustPayload,
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

describe("nthIndexOf（occ 1-based，合法边界——后端 ConsistencyValidator.occurrenceStart 同构）", () => {
  const text = "市盈率 25 倍，市净率 25 倍，又说 25 倍。";

  it("occ=1/2/3 定位各次出现起始位", () => {
    expect(nthIndexOf(text, "25 倍", 1)).toBe(text.indexOf("25 倍"));
    expect(nthIndexOf(text, "25 倍", 2)).toBe(text.indexOf("25 倍", text.indexOf("25 倍") + 1));
    expect(nthIndexOf(text, "25 倍", 3)).toBe(text.lastIndexOf("25 倍"));
  });

  it("嵌套数字出现不计次（前邻数字非法）：「5%」跳过「15%」内嵌套，命中独立出现", () => {
    const t = "涨幅15%，占比5%";
    const at = nthIndexOf(t, "5%", 1);
    expect(at).toBe(t.lastIndexOf("5%"));
    expect(t.slice(at, at + 2)).toBe("5%");
  });

  it("嵌套数字出现不计次：「3亿」跳过「13亿」、「741亿」跳过「1741亿」内嵌套", () => {
    const t1 = "营收13亿，补贴3亿";
    expect(nthIndexOf(t1, "3亿", 1)).toBe(t1.lastIndexOf("3亿"));
    const t2 = "今年1741亿，去年741亿";
    expect(nthIndexOf(t2, "741亿", 1)).toBe(t2.lastIndexOf("741亿"));
  });

  it("嵌套小数不计次：「0.5元」跳过「10.5元」内嵌套（前邻 '1'）", () => {
    const t = "票价10.5元，手续费0.5元";
    expect(nthIndexOf(t, "0.5元", 1)).toBe(t.lastIndexOf("0.5元"));
  });

  it("后邻数字非法：「174」不命中「1741亿」内的出现", () => {
    const t = "今年1741亿，去年174亿";
    expect(nthIndexOf(t, "174", 1)).toBe(t.lastIndexOf("174"));
  });

  it("后邻续小数非法：「1741」不命中「1741.5 亿」内的出现", () => {
    const t = "区间1741.5 亿，均值1741 亿";
    expect(nthIndexOf(t, "1741", 1)).toBe(t.lastIndexOf("1741"));
  });

  it("前邻千分位逗号 / 小数点非法：「741」不命中「1,741亿」「1.741亿」内的出现", () => {
    const t = "共1,741亿或1.741亿，另有741亿";
    expect(nthIndexOf(t, "741", 1)).toBe(t.lastIndexOf("741"));
  });

  it("符号前缀豁免前邻检查：「-2000元」在「1500-2000元」内合法", () => {
    const t = "1500-2000元";
    expect(nthIndexOf(t, "-2000元", 1)).toBe(t.indexOf("-2000元"));
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

  // MS-29 F4：snapshot 只读快照导出（agentMessagesToHistory 携带取数入口）
  it("snapshot：内容与 get 一致；返回副本，外方改动不影响 store（防外泄写）", () => {
    const store = createTrustStore();
    store.applyAnchors("m1", anchorsValue("m1").payload);
    store.applyCorrection("m2", "note-1");
    const snap = store.snapshot();
    expect(snap.size).toBe(2);
    expect(snap.get("m1")).toBe(store.get("m1"));
    expect(snap.get("m2")?.correction).toEqual({ notes: ["note-1"] });
    // 副本隔离：清空快照不改内部表
    (snap as Map<string, TrustPayload>).clear();
    expect(store.get("m1")?.stats.verified).toBe(1);
    expect(store.snapshot().size).toBe(2);
  });

  // MS-29 F5：rebuild（历史回灌整表重建）——F1 报告契约：整表替换 + 一次 notify +
  // 同步清修正台账（回灌文本已含后端改写，occ 基线以回灌 content 重开）；空表即全清。
  describe("rebuild（F5 历史回灌整表重建）", () => {
    it("带 payload 批量入：整表替换旧数据、传入引用原样落地、订阅一次通知", () => {
      const store = createTrustStore();
      store.applyAnchors("old-1", anchorsPayload());
      store.applyCorrection("old-2", "note");
      const listener = vi.fn();
      store.subscribe(listener);
      const e1 = anchorsPayload();
      const e2 = anchorsPayload({
        stats: { verified: 0, sourced: 2, unverified: 0 },
        correction: { notes: ["note-x"] },
      });
      store.rebuild([
        ["m1", e1],
        ["m2", e2],
      ]);
      // 整表替换：上一会话（或回灌前）数据清除
      expect(store.get("old-1")).toBeUndefined();
      expect(store.get("old-2")).toBeUndefined();
      // 条目写入：传入引用原样落地（rebuild 后同引用契约，F2 useSyncExternalStore 依赖）
      expect(store.get("m1")).toBe(e1);
      expect(store.get("m2")?.correction).toEqual({ notes: ["note-x"] });
      expect(store.snapshot().size).toBe(2);
      // 一次 notify（整批通知，非逐条）
      expect(listener).toHaveBeenCalledTimes(1);
    });

    it("空表全清：payloads 与修正台账同步清（rebuild 语义 = 新基线）", () => {
      const store = createTrustStore();
      // 先落 payload + 台账（correction 分派器为台账唯一写方，直接构造已登记形态）
      store.applyCorrection("m1", "note-1");
      const ledger = store.correctionLedger("m1");
      ledger.originalContent = "营收 1741 亿。";
      ledger.lastContent = "营收 1708 亿。";
      ledger.records = [{ snippet: "1741 亿", occ: 1, replacement: "1708 亿", start: 3 }];
      const listener = vi.fn();
      store.subscribe(listener);
      store.rebuild([]);
      expect(store.get("m1")).toBeUndefined();
      expect(store.snapshot().size).toBe(0);
      expect(listener).toHaveBeenCalledTimes(1);
      // 台账已清：再次取的是全新空台账（旧引用不再被 store 持有，occ 解析基线重开）
      const fresh = store.correctionLedger("m1");
      expect(fresh).not.toBe(ledger);
      expect(fresh).toEqual({ originalContent: null, lastContent: null, records: [] });
    });

    it("rebuild 后事件写入照常：applyAnchors 幂等覆盖语义在新基线上不变", () => {
      const store = createTrustStore();
      store.rebuild([["m1", anchorsPayload()]]);
      store.applyAnchors("m1", anchorsPayload({ stats: { verified: 5, sourced: 0, unverified: 0 } }));
      expect(store.get("m1")?.stats.verified).toBe(5);
    });
  });
});

// MS-29 F5：持久化 payload 文本（JSON 文本）→ TrustPayload 宽松解析。
// 回灌侧行级守卫：非法 JSON / schema 不符 / v≠1 返回 null（跳过该行不炸整批）。
describe("parseTrustPayload（F5 持久化 payload 文本解析）", () => {
  it("合法 JSON 文本 → TrustPayload（未知键 strip、可选半边保留）", () => {
    const text = JSON.stringify({
      ...anchorsPayload(),
      correction: { notes: ["n"] },
      extraTop: "x",
    });
    const parsed = parseTrustPayload(text);
    expect(parsed).not.toBeNull();
    expect(parsed).not.toHaveProperty("extraTop");
    expect(parsed?.stats.verified).toBe(1);
    expect(parsed?.correction).toEqual({ notes: ["n"] });
  });

  it("非法 JSON / 空串 → null（回灌行级跳过）", () => {
    expect(parseTrustPayload("{broken")).toBeNull();
    expect(parseTrustPayload("")).toBeNull();
  });

  it("schema 不符（缺 stats）/ v≠1 → null（版本门：不猜未来结构）", () => {
    expect(parseTrustPayload(JSON.stringify({ v: 1, anchors: [] }))).toBeNull();
    expect(parseTrustPayload(JSON.stringify({ ...anchorsPayload(), v: 2 }))).toBeNull();
  });

  it("非对象 JSON（数字 / 字符串 / null）→ null", () => {
    expect(parseTrustPayload("42")).toBeNull();
    expect(parseTrustPayload('"文本"')).toBeNull();
    expect(parseTrustPayload("null")).toBeNull();
  });
});

describe("replaceSnippetInMessages（原位替换 + 修正台账）", () => {
  const content = "市盈率 25 倍，市净率 25 倍。";
  const messages: Message[] = [
    { id: "u1", role: "user", content: "看看估值" } as Message,
    assistantMsg("a1", content),
    assistantMsg("a2", "无关消息"),
  ];

  it("替换 occ 第 2 次出现；其余消息引用不变", () => {
    const ledger = createTrustStore().correctionLedger("a1");
    const next = replaceSnippetInMessages(messages, "a1", { snippet: "25 倍", occ: 2, replacement: "31 倍" }, ledger);
    expect(next).not.toBeNull();
    expect(next![1].content).toBe("市盈率 25 倍，市净率 31 倍。");
    expect(next![0]).toBe(messages[0]);
    expect(next![2]).toBe(messages[2]);
  });

  it("同 snippet 多条修正：occ 均对首条到达时的原文解析（后端 applyReplacements 单 body 语义）", () => {
    const ledger = createTrustStore().correctionLedger("a1");
    let msgs: readonly Message[] = [assistantMsg("a1", "A 1741 亿 B 1741 亿 C 1741 亿 D")];
    msgs = replaceSnippetInMessages(msgs, "a1", { snippet: "1741 亿", occ: 1, replacement: "1708 亿" }, ledger)!;
    expect(msgs[0].content).toBe("A 1708 亿 B 1741 亿 C 1741 亿 D");
    // 第 2 条 occ=3 定义在改写前原文上（原文有 3 处）；对已改写文本顺序应用不得把 occ 当
    // 「当前内容第 3 次」解析——否则越界丢弃、与后端落盘的改写文分歧
    msgs = replaceSnippetInMessages(msgs, "a1", { snippet: "1741 亿", occ: 3, replacement: "1696 亿" }, ledger)!;
    expect(msgs[0].content).toBe("A 1708 亿 B 1741 亿 C 1696 亿 D");
  });

  it("重复事件（同 snippet/occ）：对原文重解析命中同区间，幂等重建出相同内容（不误替下一次出现）", () => {
    const ledger = createTrustStore().correctionLedger("a1");
    let msgs: readonly Message[] = [assistantMsg("a1", "营收 1741 亿（他处 1741 亿）。")];
    msgs = replaceSnippetInMessages(msgs, "a1", { snippet: "1741 亿", occ: 2, replacement: "1708 亿" }, ledger)!;
    expect(msgs[0].content).toBe("营收 1741 亿（他处 1708 亿）。");
    // 旧实现的洞：重放事件在已改写文本上只剩 1 处 → -1 丢弃；或若还有第 3 处会误替。
    // 台账语义：occ 恒对原文解析 → 同区间 → 重建结果不变
    const again = replaceSnippetInMessages(msgs, "a1", { snippet: "1741 亿", occ: 2, replacement: "1708 亿" }, ledger)!;
    expect(again[0].content).toBe("营收 1741 亿（他处 1708 亿）。");
  });

  it("occ 越界 / 消息不存在 / 非字符串 content 返回 null 且台账不变", () => {
    const store = createTrustStore();
    const ledger = store.correctionLedger("a1");
    expect(replaceSnippetInMessages(messages, "a1", { snippet: "25 倍", occ: 3, replacement: "x" }, ledger)).toBeNull();
    expect(replaceSnippetInMessages(messages, "nope", { snippet: "25 倍", occ: 1, replacement: "x" }, store.correctionLedger("nope"))).toBeNull();
    const complex = [{ type: "text", text: "hi" }] as unknown as string;
    expect(
      replaceSnippetInMessages([assistantMsg("a3", complex)], "a3", { snippet: "hi", occ: 1, replacement: "x" }, store.correctionLedger("a3")),
    ).toBeNull();
    expect(ledger.records).toHaveLength(0);
    expect(ledger.originalContent).toBeNull();
  });

  it("外部改写（历史回灌 setMessages）后：台账基线重置，新修正对新原文解析", () => {
    const store = createTrustStore();
    const ledger = store.correctionLedger("a1");
    let msgs: readonly Message[] = [assistantMsg("a1", "市盈率 25 倍。")];
    msgs = replaceSnippetInMessages(msgs, "a1", { snippet: "25 倍", occ: 1, replacement: "31 倍" }, ledger)!;
    expect(msgs[0].content).toBe("市盈率 31 倍。");
    // 模拟回灌：服务端已落盘文本整体替换（与 lastContent 不一致 → 基线重置）
    const hydrated = [assistantMsg("a1", "市盈率 41 倍。")];
    const next = replaceSnippetInMessages(hydrated, "a1", { snippet: "41 倍", occ: 1, replacement: "39 倍" }, ledger)!;
    expect(next[0].content).toBe("市盈率 39 倍。");
    expect(ledger.records).toHaveLength(1);
  });

  it("不改入参数组与消息对象（库管线 dev/test 下深冻结 params.messages）", () => {
    const ledger = createTrustStore().correctionLedger("a1");
    const frozen = messages.map((m) => Object.freeze(m)) as readonly Message[];
    const next = replaceSnippetInMessages(frozen, "a1", { snippet: "25 倍", occ: 2, replacement: "31 倍" }, ledger);
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

  it("重复 correction 事件：对原文幂等重建（同结果不误替），注记不重复", () => {
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
    // 幂等：occ 对台账基线原文解析命中同区间，重建结果不变
    expect(second?.messages?.[0].content).toBe("营收 1708 亿。");
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

// ———— MS-29 后续①：anchors 到达时剥离自声明 advice 标记行（后端 AdviceDetector 同语义） ————

describe("advice 标记剥离（trust.anchors 分支接线）", () => {
  it("无标记直通：不产 mutation，content 原样（幂等零变换）", () => {
    const store = createTrustStore();
    const messages = [assistantMsg("a1", "茅台现价1520.33元。")];
    const result = handleTrustCustomEvent(
      { name: TRUST_ANCHORS_EVENT, value: anchorsValue("a1") },
      messages,
      store,
    );
    expect(result).toBeUndefined();
    expect(messages[0].content).toBe("茅台现价1520.33元。");
    expect(store.get("a1")?.stats.verified).toBe(1);
  });

  it("文末标记行被剥离：返回 AgentStateMutation，content 为干净文本", () => {
    const store = createTrustStore();
    const result = handleTrustCustomEvent(
      { name: TRUST_ANCHORS_EVENT, value: anchorsValue("a1") },
      [assistantMsg("a1", "茅台现价1520.33元。\n<!--advice-->")],
      store,
    );
    expect(result).toEqual({
      messages: [expect.objectContaining({ id: "a1", content: "茅台现价1520.33元。" })],
    });
  });

  it("标记行前后空行清理与后端 stripTrailing 同构：「正文\\n\\n标记」→「正文」", () => {
    const store = createTrustStore();
    const result = handleTrustCustomEvent(
      { name: TRUST_ANCHORS_EVENT, value: anchorsValue("a1") },
      [assistantMsg("a1", "正文\n\n<!--advice-->")],
      store,
    );
    expect(result?.messages?.[0].content).toBe("正文");
  });

  it("行内非独立同形字符串不动：前后有正文文字的行不剥离、零 mutation", () => {
    const store = createTrustStore();
    const inline = [assistantMsg("a1", "说明见 <!--advice--> 标记。\n前置文字<!--advice-->")];
    const result = handleTrustCustomEvent(
      { name: TRUST_ANCHORS_EVENT, value: anchorsValue("a1") },
      inline,
      store,
    );
    expect(result).toBeUndefined();
    expect(inline[0].content).toBe("说明见 <!--advice--> 标记。\n前置文字<!--advice-->");
  });

  it("与 correction 组合：替换先落位，anchors 剥离在替换后的现值 content 上做", () => {
    const store = createTrustStore();
    let messages: readonly Message[] = [assistantMsg("a1", "茅台现价15.20元。\n<!--advice-->")];
    messages = handleTrustCustomEvent(
      { name: TRUST_CORRECTION_EVENT, value: correctionValue("a1", "15.20元", 1, "1520.33元") },
      messages,
      store,
    )!.messages!;
    expect(messages[0].content).toBe("茅台现价1520.33元。\n<!--advice-->");
    const result = handleTrustCustomEvent(
      { name: TRUST_ANCHORS_EVENT, value: anchorsValue("a1") },
      messages,
      store,
    );
    expect(result?.messages?.[0].content).toBe("茅台现价1520.33元。");
  });

  it("消息不存在 / 非字符串 content：store 照常落地，不产 mutation", () => {
    const store = createTrustStore();
    expect(
      handleTrustCustomEvent(
        { name: TRUST_ANCHORS_EVENT, value: anchorsValue("ghost") },
        [assistantMsg("a1", "带标记\n<!--advice-->")],
        store,
      ),
    ).toBeUndefined();
    expect(store.get("ghost")?.stats.verified).toBe(1);
    const complex = [{ type: "text", text: "hi" }] as unknown as string;
    expect(
      handleTrustCustomEvent(
        { name: TRUST_ANCHORS_EVENT, value: anchorsValue("a3") },
        [assistantMsg("a3", complex)],
        store,
      ),
    ).toBeUndefined();
  });
});
