import type { TrustAdvice, TrustStats } from "@/lib/trustMeta";
import { useTrustPayload } from "@/components/shared/TrustMarkdownView";

// MS-29 F3：低置信横幅 + disclaimer（设计规格 §5.3，需求 F03 决策 #3/#4）。
// 数据源：trust.anchors payload 的 confidence.signals / advice 半边（B6/B7 契约——
// confidence 键任一信号命中才携带、advice 键命中才携带，缺键=不渲染零开销）。
// 只读组件，渲染在 AssistantMessage 正文与反馈条之间（ThreadArea 接线）。

/** 信号线名：「线名:值」串的 name 半边（按名分派，corrections/correction_failed 同族各列）。 */
function signalName(signal: string): string {
  const idx = signal.indexOf(":");
  return idx === -1 ? signal : signal.slice(0, idx);
}

/**
 * unverified_ratio **计数优先**（需求拍板 #3 预览原文「· 3 处数字未溯源」）：
 * `payload.stats.unverified` 恒随 anchors payload 携带，正常路径永走计数；
 * stats 缺失（裸用组件）回退「约 X%」——按**解析后的数字**展示（B7 展示值两位小数
 * 去一位尾零如 0.4/0.75，勿对线串做字符串匹配），值 trim 后缺失/不可解析降级无数字文案。
 */
function unverifiedRatioText(signal: string, stats?: TrustStats | null): string {
  const count = stats?.unverified;
  if (typeof count === "number" && Number.isFinite(count)) {
    return `${count} 处数字未溯源`;
  }
  const idx = signal.indexOf(":");
  const value = (idx === -1 ? "" : signal.slice(idx + 1)).trim();
  const ratio = Number(value);
  if (value === "" || !Number.isFinite(ratio)) return "数字未溯源";
  return `约 ${Math.round(ratio * 100)}% 数字未溯源`;
}

/** 命中信号 → 人话文案（决策 #3 映射）；未知信号名返回 null（宁可少标，未来信号不误示）。 */
function signalText(signal: string, stats?: TrustStats | null): string | null {
  const name = signalName(signal);
  if (name === "unverified_ratio") return unverifiedRatioText(signal, stats);
  if (name === "stale_quotes" || name === "stale_financials" || name === "stale_macro") {
    return "数据时间戳陈旧";
  }
  if (name === "tool_failures") return "工具调用失败";
  if (name === "corrections" || name === "correction_failed") return "校验修正已介入";
  return null;
}

/**
 * 核实路径静态映射（决策 #3，v1 简单可解释）：按信号**线序**取首个可映射信号的建议
 * （非固定优先级——信号顺序即 wire 固定线序），无映射落通用建议。
 */
function suggestionFor(signals: readonly string[]): string {
  for (const signal of signals) {
    const name = signalName(signal);
    if (name === "unverified_ratio" || name === "stale_quotes") {
      return "建议重问最新价或查看东方财富行情页";
    }
    if (name === "stale_financials") return "巨潮资讯网";
    if (name === "stale_macro") return "统计局/央行官网";
  }
  return "建议核实数据";
}

/** 低置信横幅：列命中信号（人话）+ 核实路径建议。signals 缺/空/全未知 → 不渲染。 */
export function ConfidenceBanner({
  signals,
  stats,
}: {
  signals?: readonly string[] | null;
  /** unverified_ratio 计数口径来源（payload 恒携带；缺失回退约百分比形态） */
  stats?: TrustStats | null;
}) {
  if (!signals || signals.length === 0) return null;
  const texts = signals
    .map((signal) => ({ name: signalName(signal), text: signalText(signal, stats) }))
    .filter((x): x is { name: string; text: string } => x.text !== null);
  if (texts.length === 0) return null;
  return (
    <div
      data-testid="confidence-banner"
      className="mt-2 rounded-lg border border-[color:var(--color-accent)]/40 bg-[color:var(--color-panel)] px-3.5 py-2.5 text-[12px] leading-relaxed"
    >
      <p className="font-medium text-[color:var(--color-accent)]">⚠ 低置信提示</p>
      <ul className="mt-1 list-disc pl-4 text-[color:var(--color-ink-dim)]">
        {texts.map(({ name, text }, i) => (
          <li key={`${name}#${i}`} data-testid="confidence-signal" data-signal={name}>
            {text}
          </li>
        ))}
      </ul>
      <p data-testid="confidence-suggestion" className="mt-1 text-[color:var(--color-ink-faint)]">
        {suggestionFor(signals)}
      </p>
    </div>
  );
}

/** by 小标签（B6：self=模型自声明 / lexicon=后端词表兜底 / both=两层都命中；未知值原样展示）。 */
const BY_LABEL: Record<string, string> = { self: "自声明", lexicon: "词表", both: "两者" };

/**
 * disclaimer（决策 #4）：文案为后端 `invest.trust.disclaimer-text` 经 payload.advice.text
 * 透传（前端不内置常量）；by 展示为小标签。advice 缺键 / flag 非 true / text 缺失
 * （后端配置空白）→ 不渲染。
 */
export function DisclaimerNote({ advice }: { advice?: TrustAdvice | null }) {
  if (!advice || advice.flag !== true || !advice.text) return null;
  return (
    <div
      data-testid="disclaimer-note"
      className="mt-2 flex items-start gap-2 rounded-lg border border-[color:var(--color-line-soft)] bg-[color:var(--color-bg-soft)] px-3.5 py-2 text-[12px] leading-relaxed text-[color:var(--color-ink-faint)]"
    >
      <span
        data-testid="disclaimer-by"
        className="mt-px shrink-0 rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-dim)]"
      >
        {BY_LABEL[advice.by] ?? advice.by}
      </span>
      <span className="min-w-0">{advice.text}</span>
    </div>
  );
}

/**
 * AssistantMessage 的横幅/disclaimer 渲染入口：与 F2 TrustMessageContent **同消息级独立订阅**
 * （useSyncExternalStore 引用稳定快照，F1 契约形态），插在正文与反馈条之间。
 * 两组件并存不互斥；payload 无对应键零渲染（AssistantMessage memo 比较器无需 trust 项）。
 */
export function TrustMessageAdvisories({ messageId }: { messageId: string }) {
  const payload = useTrustPayload(messageId);
  return (
    <>
      <ConfidenceBanner signals={payload?.confidence?.signals} stats={payload?.stats} />
      <DisclaimerNote advice={payload?.advice} />
    </>
  );
}
