import { memo, useSyncExternalStore } from "react";
import ReactMarkdown, { type Components } from "react-markdown";
import remarkGfm from "remark-gfm";
import { CodeBlock, InlineCode } from "@/components/chat/CodeHighlight";
import MarkdownView from "@/components/shared/MarkdownView";
import { AnchorBadge } from "@/components/shared/AnchorBadge";
import { remarkTrustAnchors, TRUST_ANCHOR_IDX_PROP } from "@/lib/trustAnchorsRemark";
import { trustStore, type TrustAnchor, type TrustPayload } from "@/lib/trustMeta";

// MS-29 F2：带溯源角标的 markdown 渲染（设计规格 §5.2）。
// MarkdownView 零改动约束：角标路径在本组件内自持一份同集 components/urlTransform
// （与 MarkdownView 手动同步）；无 anchors 时直接复用 MarkdownView，零额外开销。

const DEFAULT_CLASS = "md-body text-[14px] leading-relaxed text-[color:var(--color-ink)]";

/** 信任 payload 订阅（F1 契约）：引用稳定快照，无变更同引用。 */
export function useTrustPayload(messageId: string): TrustPayload | undefined {
  return useSyncExternalStore(trustStore.subscribe, () => trustStore.get(messageId));
}

function TrustMarkdownView({
  content,
  anchors,
  className = DEFAULT_CLASS,
}: {
  content: string;
  /** 无 / 空数组 → 原样走 MarkdownView（零开销路径） */
  anchors?: readonly TrustAnchor[] | null;
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
      // 无对应锚（防御，正常不可达）：还原普通 sup，宁可少标不可崩
      return anchor ? (
        <AnchorBadge anchor={anchor} label={idx + 1}>
          {props.children}
        </AnchorBadge>
      ) : (
        <sup>{props.children}</sup>
      );
    },
  };
  return (
    <div className={className}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm, [remarkTrustAnchors, { content, anchors }]]}
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
 * AssistantMessage 的信任渲染入口：订阅 trustStore，有锚走 TrustMarkdownView、
 * 无锚走原 MarkdownView。**独立子组件订阅**（F1 报告建议形态）：AssistantMessage 的
 * memo 比较器只看 message/toolMessage 引用，trust 数据带外——store 事件只重渲染本
 * 组件，不触发整列表重渲染，也绕开 CopilotKit 两套 context 实例的坑。
 */
export function TrustMessageContent({ messageId, content }: { messageId: string; content: string }) {
  const payload = useTrustPayload(messageId);
  return <TrustMarkdownView content={content} anchors={payload?.anchors} />;
}

export default memo(TrustMarkdownView);
