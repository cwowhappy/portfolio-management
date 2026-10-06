// MS-29 F1：可信溯源事件的带外数据接入（trust.anchors / trust.correction，设计规格 §4.3）。
//
// 线上契约（后端 TrustAgentHook / TrustTurnReport#toPayload 同构，无 null 用缺键）：
//   trust.anchors    value = {messageId, payload}   payload v1 见 TrustPayload
//   trust.correction value = {messageId, snippet, occ, replacement, note}   先于 anchors 到达
// occ 为 snippet 在该消息中的第几次出现（1-based，含非数据性出现——后端 NumberExtractor 口径）。
//
// 分层：本模块是纯 TS（零 React/零 CopilotKit 依赖），ThreadArea 的 onCustomEvent 订阅是
// 唯一接线点；渲染消费（徽标/注记）归 F2，经 subscribe + get 的 useSyncExternalStore 契约取数。

import { z } from "zod";
import type { AgentStateMutation, Message } from "@ag-ui/client";

// ———— 线上类型 ————

export const TRUST_ANCHORS_EVENT = "trust.anchors";
export const TRUST_CORRECTION_EVENT = "trust.correction";

export type TrustAnchorState = "verified" | "sourced" | "unverified";
export type TrustAsOfKind = "data" | "generated" | "call";

/** 单数字锚定：sourced/verified 态必有 tool/args/asOf/asOfKind（unverified 缺键），raw 为工具返回原值。 */
export interface TrustAnchor {
  snippet: string;
  occ: number;
  state: TrustAnchorState;
  tool?: string;
  args?: Record<string, unknown>;
  asOf?: string;
  asOfKind?: TrustAsOfKind;
  raw?: string;
}

export interface TrustStats {
  verified: number;
  sourced: number;
  unverified: number;
}

/** advice 半边（B6）：flag=任一层命中；by=self|lexicon|both；text=disclaimer（缺键表达无建议）。 */
export interface TrustAdvice {
  flag: boolean;
  by: string;
  text?: string;
}

/** payload v1（§2.1）：correction/advice/confidence 仅命中时携带（缺键表达缺省）。 */
export interface TrustPayload {
  v: 1;
  anchors: TrustAnchor[];
  stats: TrustStats;
  correction?: { notes: string[] };
  advice?: TrustAdvice;
  confidence?: { signals: string[] };
}

// ———— zod schema（宽松解析：未知字段 strip、整体失败→忽略事件、v≠1 版本门）————

const TrustAnchorSchema = z.object({
  snippet: z.string(),
  occ: z.number().int().positive(),
  state: z.enum(["verified", "sourced", "unverified"]),
  tool: z.string().optional(),
  args: z.record(z.unknown()).optional(),
  asOf: z.string().optional(),
  asOfKind: z.enum(["data", "generated", "call"]).optional(),
  raw: z.string().optional(),
});

export const TrustPayloadSchema = z.object({
  // 版本门：非 1 解析失败 → 事件整体忽略（未来 v2 走独立分支，不猜结构）
  v: z.literal(1),
  anchors: z.array(TrustAnchorSchema),
  stats: z.object({
    verified: z.number(),
    sourced: z.number(),
    unverified: z.number(),
  }),
  correction: z.object({ notes: z.array(z.string()) }).optional(),
  advice: z.object({ flag: z.boolean(), by: z.string(), text: z.string().optional() }).optional(),
  confidence: z.object({ signals: z.array(z.string()) }).optional(),
});

/**
 * F5：持久化 payload 文本（F4 携带 / GET 回带的 JSON 文本）→ TrustPayload。
 * 宽松解析：非法 JSON / schema 不符 / v≠1 均返回 null（回灌侧行级跳过，不炸整批——
 * 宁可少标不可断流）。zod 默认 strip 未知键，产物即干净 TrustPayload。
 */
export function parseTrustPayload(text: string): TrustPayload | null {
  let raw: unknown;
  try {
    raw = JSON.parse(text);
  } catch {
    return null;
  }
  const parsed = TrustPayloadSchema.safeParse(raw);
  return parsed.success ? parsed.data : null;
}

/** trust.anchors 事件 value 信封。 */
const AnchorsEventValueSchema = z.object({
  messageId: z.string().min(1),
  payload: TrustPayloadSchema,
});

/** trust.correction 事件 value 信封（单条替换一个事件，先于 anchors 到达）。 */
const CorrectionEventValueSchema = z.object({
  messageId: z.string().min(1),
  snippet: z.string(),
  occ: z.number().int().positive(),
  replacement: z.string(),
  note: z.string(),
});

// ———— TrustMeta store ————

/**
 * `Map<messageId, TrustPayload>` 的受控封装。
 *
 * 形态选择：外部 store + 引用稳定快照（get 无变更返回同引用、变更换新引用），
 * F2 用 `useSyncExternalStore(store.subscribe, () => store.get(messageId))` 消费——
 * AssistantMessage 的 memo 比较器只看 message 引用（trust 数据带外，不能触发整列表重渲染），
 * 该契约让徽标子组件独立订阅、精准重渲染；也不碰 CopilotKit 两套 context 实例的坑。
 *
 * F4（快照）/F5（批量重建）扩展位已落地：snapshot() 只读导出 + rebuild(entries) 整表替换
 * （rebuild 同步清空修正台账——回灌文本已含后端改写，occ 基线须以回灌 content 重开）。
 */
export interface TrustStore {
  /** 某消息的信任 payload；未落地返回 undefined。引用稳定：仅在该消息数据变更后换新引用。 */
  get(messageId: string): TrustPayload | undefined;
  /**
   * anchors 事件落地：同 messageId 幂等覆盖（后到整体覆盖）；先到 correction 事件的注记
   * 并入不被冲掉（correction 先于 anchors 到达是契约顺序）。
   */
  applyAnchors(messageId: string, payload: TrustPayload): void;
  /** correction 事件落地：注记并入（按文案去重幂等）；anchors 未到时先落占位条目。 */
  applyCorrection(messageId: string, note: string): void;
  /**
   * 某消息的修正台账（惰性创建）：分派器在原位替换时持有并更新（唯一写方），
   * F2（差异渲染）/F4（快照）只读消费。
   */
  correctionLedger(messageId: string): TrustCorrectionLedger;
  /**
   * F4 只读快照导出：payloads 表的**副本** Map——外方改动不影响内部状态（防外泄写）；
   * 持久化携带（agentMessagesToHistory）在此一次性取数，避免逐消息 get。
   */
  snapshot(): ReadonlyMap<string, TrustPayload>;
  /**
   * F5 批量重建（历史回灌）：整表替换——清空既有 payloads 与**修正台账**后写入 entries，
   * 一次 notify。回灌文本已含后端改写，occ 解析基线必须以回灌 content 重开（整表替换 =
   * 新基线），故台账随 payloads 同步清。空 entries 即全清（切会话语义：新会话无历史
   * 锚定，store 不残留上一会话数据）。rebuild 后 get/引用稳定契约不变（传入引用原样落地）。
   */
  rebuild(entries: Iterable<[string, TrustPayload]>): void;
  /** 订阅任何变更（useSyncExternalStore 入口）；返回退订函数。 */
  subscribe(listener: () => void): () => void;
}

/** 空占位（correction 先到、anchors 未到）：stats 全 0 + anchors 空。 */
function placeholderPayload(): TrustPayload {
  return { v: 1, anchors: [], stats: { verified: 0, sourced: 0, unverified: 0 } };
}

export function createTrustStore(): TrustStore {
  const payloads = new Map<string, TrustPayload>();
  const ledgers = new Map<string, TrustCorrectionLedger>();
  const listeners = new Set<() => void>();
  return {
    get: (messageId) => payloads.get(messageId),
    applyAnchors(messageId, payload) {
      const pending = payloads.get(messageId)?.correction?.notes ?? [];
      if (pending.length === 0) {
        payloads.set(messageId, payload);
      } else {
        // 并集保序去重：wire 自带 notes 在前（该轮回条的权威全量），先到事件注记补充在后
        const notes = [...(payload.correction?.notes ?? [])];
        for (const n of pending) if (!notes.includes(n)) notes.push(n);
        payloads.set(messageId, { ...payload, correction: { notes } });
      }
      listeners.forEach((l) => l());
    },
    applyCorrection(messageId, note) {
      const existing = payloads.get(messageId);
      const notes = existing?.correction?.notes ?? [];
      if (notes.includes(note)) return; // 重复 correction 事件：注记幂等
      payloads.set(messageId, {
        ...(existing ?? placeholderPayload()),
        correction: { notes: [...notes, note] },
      });
      listeners.forEach((l) => l());
    },
    correctionLedger(messageId) {
      let ledger = ledgers.get(messageId);
      if (!ledger) {
        ledger = { originalContent: null, lastContent: null, records: [] };
        ledgers.set(messageId, ledger);
      }
      return ledger;
    },
    snapshot: () => new Map(payloads),
    rebuild(entries) {
      // 整表替换（先清 payloads 与台账再写入）：F1 契约——rebuild 语义 = 新基线，
      // 台账须同步清（occ 恒对原文解析，回灌 content 已含后端改写，旧基线作废）
      payloads.clear();
      ledgers.clear();
      for (const [messageId, payload] of entries) payloads.set(messageId, payload);
      listeners.forEach((l) => l());
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/** 页面级单例：ThreadArea 订阅落库、F2 渲染消费同一实例。 */
export const trustStore: TrustStore = createTrustStore();

// ———— 合法边界定位（后端 ConsistencyValidator.occurrenceStart / validBoundary 同构移植）————

/** ASCII 数字（对齐后端 validBoundary 的 '0'..'9' 显式区间判断）。 */
function isAsciiDigit(c: string): boolean {
  return c >= "0" && c <= "9";
}

/** Unicode Nd 数字（对齐后端 Character.isDigit(char) 的 UTF-16 code unit 语义）。 */
function isUnicodeDigit(c: string): boolean {
  return /\p{Nd}/u.test(c);
}

/** snippet 是否以符号前缀开头（后端 isSignPrefixed：'-'/'+' 开头豁免前邻检查）。 */
function isSignPrefixed(snippet: string): boolean {
  return snippet.length > 0 && (snippet[0] === "-" || snippet[0] === "+");
}

/**
 * 合法边界（后端 validBoundary 同构）：非符号前缀时前邻非数字/逗号/小数点（千分位、嵌套数字
 * 均非法）；后邻非数字、非「小数点+数字」续小数。符号前缀（如「1500-2000元」的 -2000元）
 * 由符号切断与前数字的连续，跳过前邻检查。
 */
function validBoundary(text: string, start: number, length: number, signPrefixed: boolean): boolean {
  const before = start > 0 ? text[start - 1] : "\0";
  if (!signPrefixed && (before === "," || before === "." || isAsciiDigit(before))) {
    return false;
  }
  const afterIndex = start + length;
  const after = afterIndex < text.length ? text[afterIndex] : "\0";
  if (isAsciiDigit(after)) {
    return false;
  }
  if (after === "." && afterIndex + 1 < text.length && isUnicodeDigit(text[afterIndex + 1])) {
    return false;
  }
  return true;
}

/**
 * snippet 第 occ 次「合法出现」的起始位（1-based，含非数据性出现）；未找到返回 -1。
 * 后端 `ConsistencyValidator.occurrenceStart`（backend .../trust/ConsistencyValidator.java
 * :341-380）逐语义移植：边界非法的出现不计次（嵌套数字如「15%」里的「5%」被前邻 '1' 判
 * 非法而跳过），扫描步进 `index + 1`（逐字符右移而非跳过整个 snippet）。
 * **F2 徽标/锚定渲染定位必须复用本函数，禁用裸 indexOf 计数**——否则嵌套数字误定位。
 */
export function nthIndexOf(text: string, snippet: string, occ: number): number {
  if (snippet === "" || occ < 1) return -1;
  const signPrefixed = isSignPrefixed(snippet);
  let index = 0;
  for (let seen = 0; seen < occ; ) {
    index = text.indexOf(snippet, index);
    if (index === -1) return -1;
    if (validBoundary(text, index, snippet.length, signPrefixed)) {
      seen++;
      if (seen === occ) return index;
    }
    index += 1;
  }
  return -1;
}

// ———— 原位替换（修正台账：occ 对改写前原文解析——后端 applyReplacements 单 body 语义）————

/** 一条已登记修正：snippet+occ 在台账基线原文中的定位 + 回写串。 */
export interface TrustCorrectionRecord {
  snippet: string;
  occ: number;
  replacement: string;
  /** 在基线原文中的起始位（全部记录自右向左一次性回写） */
  start: number;
}

/**
 * 修正台账（store 内按 messageId 持有）：后端一轮替换的全部偏差区间在**同一原文**上解析、
 * 自右向左回写（applyReplacements）；前端事件逐条到达，occ 又定义在改写前原文上——台账
 * 保存首条修正到达时的原文作解析基线，每条新修正对原文解析后全量重建，语义等价后端。
 */
export interface TrustCorrectionLedger {
  /** 首条修正到达时的消息原文（occ 解析基线）；null = 尚无修正 */
  originalContent: string | null;
  /** 最近一次重建产物；与实际 content 不一致（历史回灌等外部改写）即基线重置 */
  lastContent: string | null;
  /** 已登记修正（同 snippet+occ 后到覆盖）；含各自原文区间 */
  records: TrustCorrectionRecord[];
}

/** 后端 applyReplacements 同构：全部区间对同一基线原文、自右向左回写（左侧偏移不漂移）。 */
function applyCorrectionRecords(baseline: string, records: readonly TrustCorrectionRecord[]): string {
  let out = baseline;
  for (const r of [...records].sort((a, b) => b.start - a.start)) {
    out = out.slice(0, r.start) + r.replacement + out.slice(r.start + r.snippet.length);
  }
  return out;
}

/**
 * messages 中 id===messageId 的消息按修正台账做原位替换：新修正的 occ 对台账基线原文解析
 * （合法边界语义，见 nthIndexOf），登记后全部记录自右向左一次性回写重建 content。
 * 消息不存在 / content 非字符串 / occ 越界（对原文）返回 null 且台账不变。
 * 重复事件天然幂等：同 snippet+occ 对原文命中同区间，重建出相同内容——不会把 occ 顺延到
 * 下一次出现上。
 *
 * 不改入参：@ag-ui/client 管线在 dev/test 下深冻结 params.messages，原位改写会抛 TypeError
 * （库控制台明示须以 AgentStateMutation 返回而非直接改写）。
 */
export function replaceSnippetInMessages(
  messages: readonly Message[],
  messageId: string,
  correction: { snippet: string; occ: number; replacement: string },
  ledger: TrustCorrectionLedger,
): Message[] | null {
  const idx = messages.findIndex((m) => m.id === messageId);
  if (idx === -1) return null;
  const target = messages[idx];
  if (typeof target.content !== "string") return null;
  // 基线维护：已有基线且 content 与最近重建一致 → 沿用（occ 恒对原文解析）；否则（首条修正、
  // 或历史回灌 setMessages / 流中续写等外部改写）以当前 content 为基线重开。命中前不落账。
  const reusable = ledger.originalContent !== null && ledger.lastContent === target.content;
  // 非空收窄经 reusable 布尔传导不了（TS 控制流限制），此处 ! 由 reusable 定义保证
  const baseline = reusable ? ledger.originalContent! : target.content;
  const priorRecords = reusable ? ledger.records : [];
  const start = nthIndexOf(baseline, correction.snippet, correction.occ);
  if (start === -1) return null;
  const record: TrustCorrectionRecord = { ...correction, start };
  const records = [
    ...priorRecords.filter((r) => !(r.snippet === correction.snippet && r.occ === correction.occ)),
    record,
  ];
  const rebuilt = applyCorrectionRecords(baseline, records);
  ledger.originalContent = baseline;
  ledger.records = records;
  ledger.lastContent = rebuilt;
  const next = [...messages];
  // as Message：content 已守卫为 string；联合各成员展开后回赋 content 的收窄 TS 表达不了
  next[idx] = { ...target, content: rebuilt } as Message;
  return next;
}

// ———— advice 标记行剥离（MS-29 后续①：后端 AdviceDetector.MARKER 同语义） ————

/** 自声明 advice 标记（与后端 AdviceDetector.MARKER 同值：提示词约束模型文末单独一行输出）。 */
export const ADVICE_MARKER = "<!--advice-->";

/**
 * 标记行剥离（后端 AdviceDetector.detect 同语义）：只匹配**独立成行**的
 * `<!--advice-->`（trim 后整行相等）——行内出现（前后有正文文字）不动；有剥离时保留行
 * join 后去尾随空白（后端 stripTrailing 同构，标记常在文末故同时清掉标记前的空行）；
 * 无标记返回原串（幂等直通）。markdown 渲染本就不可见，剥的是裸文本/复制源残留。
 */
export function stripAdviceMarker(text: string): string {
  let stripped = false;
  const kept: string[] = [];
  for (const line of text.split("\n")) {
    if (line.trim() === ADVICE_MARKER) {
      stripped = true;
      continue;
    }
    kept.push(line);
  }
  return stripped ? kept.join("\n").trimEnd() : text;
}

/**
 * anchors 事件到达时对目标消息 content 做标记行剥离（后端 stateStore 副本已在 processor
 * 步骤 0 剥离，本变换补齐前端 live 文本与防抖 PUT 持久化的同语义）。与 correction 替换
 * 同一返回形态（AgentStateMutation 通道）；correction 先到先替换，剥离在替换后的现值
 * content 上做。独立轻量变换：不改数字、不触发台账定位重建（标记在文末，数字位次不受
 * 影响；correction 按契约先于 anchors，无「剥离后再替换」路径）。幂等：无标记 / 消息
 * 不存在 / 非字符串 content → null（不产 mutation）。
 */
function stripAdviceMarkerFromMessages(
  messages: readonly Message[],
  messageId: string,
): Message[] | null {
  const idx = messages.findIndex((m) => m.id === messageId);
  if (idx === -1) return null;
  const target = messages[idx];
  if (typeof target.content !== "string") return null;
  const cleaned = stripAdviceMarker(target.content);
  if (cleaned === target.content) return null;
  const next = [...messages];
  // as Message：content 已守卫为 string（与 replaceSnippetInMessages 同款收窄处理）
  next[idx] = { ...target, content: cleaned } as Message;
  return next;
}

// ———— 事件分派 ————

/** 入参宽松形态：线上 CustomEvent 的 name/value（裸对象即可，不依赖 @ag-ui/core 构造器）。 */
export interface TrustCustomEventLike {
  name?: unknown;
  value?: unknown;
}

/**
 * Custom 事件分派（ThreadArea onCustomEvent 唯一转接口）：
 * - `trust.anchors` → store.applyAnchors + 标记行剥离（MS-29 后续①：live 文本与后端
 *   stateStore 剥离语义对齐）；剥离产生新 content 时返回 `{messages}`（与 correction
 *   替换同一 sanctioned 通道），无标记直通返回 void
 * - `trust.correction` → store.applyCorrection + 原位替换；替换命中才返回
 *   `{messages}`（AgentStateMutation 的 sanctioned 路径，defaultApplyEvents 会克隆合并、
 *   processApplyEvents 写回 agent.messages）。Custom 事件在管线内无默认应用，
 *   不须 stopPropagation。
 * - 未知 name / 解析失败 / v≠1：安全忽略（返回 void，绝不抛——宁可少标不可断流）。
 * - 注记无论替换是否命中都落 store：correction 可能先于 anchors 到达，且重复事件替换
 *   no-op 时注记仍须可见（渲染注记语义）。
 */
export function handleTrustCustomEvent(
  event: TrustCustomEventLike | null | undefined,
  messages: readonly Message[],
  store: TrustStore = trustStore,
): AgentStateMutation | void {
  if (typeof event !== "object" || event === null) return;
  if (event.name === TRUST_ANCHORS_EVENT) {
    const parsed = AnchorsEventValueSchema.safeParse(event.value);
    if (!parsed.success) return;
    store.applyAnchors(parsed.data.messageId, parsed.data.payload);
    const stripped = stripAdviceMarkerFromMessages(messages, parsed.data.messageId);
    return stripped ? { messages: stripped } : undefined;
  }
  if (event.name === TRUST_CORRECTION_EVENT) {
    const parsed = CorrectionEventValueSchema.safeParse(event.value);
    if (!parsed.success) return;
    const { messageId, snippet, occ, replacement, note } = parsed.data;
    store.applyCorrection(messageId, note);
    const next = replaceSnippetInMessages(
      messages,
      messageId,
      { snippet, occ, replacement },
      store.correctionLedger(messageId),
    );
    return next ? { messages: next } : undefined;
  }
}
