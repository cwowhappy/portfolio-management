import { memo, useSyncExternalStore } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { CodeBlock, InlineCode } from "@/components/chat/CodeHighlight";
import MarkdownView from "@/components/shared/MarkdownView";
import { AnchorBadge } from "@/components/shared/AnchorBadge";
import {
  remarkTrustAnchors,
  TRUST_ANCHOR_FLASH_PROP,
  TRUST_ANCHOR_IDX_PROP,
} from "@/lib/trustAnchorsRemark";
import {
  trustStore,
  type TrustAnchor,
  type TrustPayload,
} from "@/lib/trustMeta";

// MS-29 F2：带溯源角标的 markdown 渲染（设计规格 §5.2）。
// MarkdownView 零改动约束：角标路径在本组件内自持一份同集 components/urlTransform
// （与 MarkdownView 手动同步）；无 anchors 时直接复用 MarkdownView，零额外开销。

const DEFAULT_CLASS = "md-body text-[14px] leading-relaxed text-[color:var(--color-ink)]";

/** 信任 payload 订阅（F1 契约）：引用稳定快照，无变更同引用。 */
export function useTrustPayload(messageId: string): TrustPayload | undefined {
  return useSyncExternalStore(trustStore.subscribe, () => trustStore.get(messageId));
}

/**
 * MS-29 后续④瞬时高亮数据源：新鲜窗口内的替换落位 snippet 集合。
 * live correction 到达时 store 打点，窗口判定与快照引用稳定都在 store 侧
 * （correctionFlash——渲染体不触 Date.now()，react-hooks/purity）；窗口过期 / 回灌
 * （rebuild 后无打点）返回 undefined（高亮不重放）。不设过期定时器：窗口内一次性动画
 * 播完即静止，类在下次重渲染时自然摘除，不引入持久 DOM 状态。
 */
function useCorrectionFlashSnippets(messageId: string): ReadonlySet<string> | undefined {
  return useSyncExternalStore(
    trustStore.subscribe,
    () => trustStore.correctionFlash(messageId),
  );
}

function TrustMarkdownView({
  content,
  anchors,
  flashSnippets,
  className = DEFAULT_CLASS,
}: {
  content: string;
  /** 无 / 空数组 → 原样走 MarkdownView（零开销路径） */
  anchors?: readonly TrustAnchor[] | null;
  /** 替换落位 snippet 集合（新鲜窗口内的修正）：对应锚定数字挂一次性高亮（后续④） */
  flashSnippets?: ReadonlySet<string>;
  className?: string;
}) {
  if (!anchors || anchors.length === 0) {
    return <MarkdownView content={content} className={className} />;
  }
  const components: Components = {
    // —— 以下四项与 MarkdownView 保持同集（零改动约束，改 MarkdownView 须手动同步）——
    pre: (p) => <>{p.children}</>,
    code: ({ className: cls, children }) => {
      // 有语言类名，或含换行（无语言围栏代码块）均按块渲染
      const isBlock =
        /language-[\w-]+/.test(cls ?? "") || String(children ?? "").includes("\n");
      return isBlock ? (
        <CodeBlock className={cls}>{children}</CodeBlock>
      ) : (
        <InlineCode>{children}</InlineCode>
      );
    },
    img: ({ src, alt, title }) => (
      // 外域 URL 来源不可枚举，以 https 门控 + no-referrer + 尺寸约束兜底。
      // eslint-disable-next-line @next/next/no-img-element
      <img
        src={src}
        alt={alt ?? ""}
        title={title}
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        className="my-2 max-h-[420px] max-w-full rounded-md border border-[color:var(--color-line-soft)]"
      />
    ),
    a: ({ href, children }) => (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        className="underline decoration-[color:var(--color-ink-faint)] underline-offset-2"
      >
        {children}
      </a>
    ),
    // —— 信任角标：remark 插件盖的 sup 走 AnchorBadge ——
    sup: (props) => {
      const raw = (props as Record<string, unknown>)[TRUST_ANCHOR_IDX_PROP];
      const idx = typeof raw === "string" || typeof raw === "number" ? Number(raw) : NaN;
      const anchor = Number.isInteger(idx) ? anchors[idx] : undefined;
      // 替换落位高亮（后续④）：新鲜窗口内的修正，其落位数字包一层一次性动画 span
      const flash = (props as Record<string, unknown>)[TRUST_ANCHOR_FLASH_PROP] !== undefined;
      // 无对应锚（防御，正常不可达）：还原普通 sup，宁可少标不可崩
      return anchor ? (
        <AnchorBadge anchor={anchor} label={idx + 1}>
          {flash ? (
            <span data-testid="trust-correction-flash" className="trust-correction-flash">
              {props.children}
            </span>
          ) : (
            props.children
          )}
        </AnchorBadge>
      ) : (
        <sup>{props.children}</sup>
      );
    },
  };
  return (
    <div className={className}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, [remarkTrustAnchors, { content, anchors, flashSnippets }]]}
        urlTransform={(url) => {
          // 与 MarkdownView 同款：默认 transform 已拦 javascript:/data:；再拦明文 http。
          if (url.startsWith("/") || url.startsWith("https://")) return url;
          return "";
        }}
        components={components}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}

/**
 * 修正注记引用块（拍板 #10「注记保留——改写痕迹可查」，F6 修复轮落地）：
 * `payload.correction.notes` 渲染在消息内容尾部。后端 stateStore 文本本带注记行
 * （「> ⚠ 校验修正：原文误述 X」，ConsistencyValidator#noteLine），但前端实时文本
 * 无注记行、防抖 PUT 会以实时文本覆盖——注记的可见性由本组件从 payload 重建，
 * live（trust.correction 事件落 store，anchors 未到的占位 payload 即携带）与
 * 回灌（F5 payload 重建）同一订阅渲染路径。与 F3 横幅 corrections 信号并存不互斥：
 * 信号是低置信概览，注记是逐条改写痕迹（原文误述值）。
 * 行形态与后端注记行同形「⚠ 校验修正：原文误述 X」（wire note = "原文误述 " + snippet）；
 * 降级注记（"校验修正失败：…"）已自带「校验修正」字样，原样渲染不叠双前缀。
 * 无 correction 键 / 空数组零渲染。
 */
export function CorrectionNotes({ notes }: { notes?: readonly string[] | null }) {
  if (!notes || notes.length === 0) return null;
  return (
    <blockquote
      data-testid="trust-correction-notes"
      className="mt-2 border-l-2 border-[color:var(--color-line-soft)] pl-3.5 text-[12px] leading-relaxed text-[color:var(--color-ink-faint)]"
    >
      {notes.map((note, i) => (
        <p key={`${note}#${i}`} className={i === 0 ? undefined : "mt-1"}>
          ⚠ {note.includes("校验修正") ? note : `校验修正：${note}`}
        </p>
      ))}
    </blockquote>
  );
}

/**
 * AssistantMessage 的信任渲染入口：订阅 trustStore，有锚走 TrustMarkdownView、
 * 无锚走原 MarkdownView。**独立子组件订阅**（F1 报告建议形态）：AssistantMessage 的
 * memo 比较器只看 message/toolMessage 引用，trust 数据带外——store 事件只重渲染本
 * 组件，不触发整列表重渲染，也绕开 CopilotKit 两套 context 实例的坑。
 * 内容尾部挂 CorrectionNotes（拍板 #10 注记保留）：live 与回灌同一订阅路径。
 * 新鲜窗口内对替换落位锚定挂一次性高亮（后续④，拍板 #10「瞬时高亮」半边——替换可见性
 * 的主通道仍是注记块 + 横幅信号，高亮仅为 live 到达时的一次性视觉反馈）。
 */
export function TrustMessageContent({ messageId, content }: { messageId: string; content: string }) {
  const payload = useTrustPayload(messageId);
  const flashSnippets = useCorrectionFlashSnippets(messageId);
  return (
    <>
      <TrustMarkdownView content={content} anchors={payload?.anchors} flashSnippets={flashSnippets} />
      <CorrectionNotes notes={payload?.correction?.notes} />
    </>
  );
}

export default memo(TrustMarkdownView);
