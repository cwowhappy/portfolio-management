import { memo } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { CodeBlock, InlineCode } from "@/components/chat/CodeHighlight";

/** chat/wiki 共用的 Markdown 渲染配置（自 ThreadArea 抽出，行为不变）。 */
function MarkdownView({ content, className = "md-body text-[14px] leading-relaxed text-[color:var(--color-ink)]" }: {
  content: string;
  className?: string;
}) {
  return (
    <div className={className}>
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        urlTransform={(url) => {
          // 默认 transform 已拦 javascript:/data:；再拦 http:（明文外域）。
          // 同源相对路径与 https 放行。
          if (url.startsWith("/") || url.startsWith("https://")) return url;
          return "";
        }}
        components={{
          pre: (p) => <>{p.children}</>,
          code: ({ className, children }) => {
            // 有语言类名，或含换行（无语言围栏代码块）均按块渲染
            const isBlock =
              /language-[\w-]+/.test(className ?? "") ||
              String(children ?? "").includes("\n");
            return isBlock ? (
              <CodeBlock className={className}>{children}</CodeBlock>
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
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}

export default memo(MarkdownView);
