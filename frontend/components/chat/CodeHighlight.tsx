"use client";

import { useState, type ReactNode } from "react";
import hljs from "highlight.js/lib/common";

function highlight(code: string, language?: string): string {
  try {
    const lang = language && hljs.getLanguage(language) ? language : "plaintext";
    return hljs.highlight(code, { language: lang }).value;
  } catch {
    return code.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
}

/** 代码块（fenced code）：语言标签 + 复制 + highlight.js 高亮。 */
export function CodeBlock({
  className,
  children,
}: {
  className?: string;
  children?: ReactNode;
}) {
  const [copied, setCopied] = useState(false);
  const language = /language-([\w-]+)/.exec(className ?? "")?.[1] ?? "text";
  const code = String(children ?? "").replace(/\n$/, "");
  const copy = () => {
    void navigator.clipboard?.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };
  return (
    <div className="my-2 overflow-hidden rounded-md">
      <div className="flex items-center justify-between rounded-t-md border border-b-0 border-[color:var(--color-line-soft)] bg-[color:var(--color-panel-2)] px-3 py-1.5">
        <span className="font-[family-name:var(--font-mono)] text-[11px] uppercase tracking-wider text-[color:var(--color-ink-faint)]">
          {language}
        </span>
        <button
          type="button"
          onClick={copy}
          className="rounded px-2 py-0.5 text-[11px] text-[color:var(--color-ink-dim)] transition-colors hover:text-[color:var(--color-up)]"
        >
          {copied ? "已复制" : "复制"}
        </button>
      </div>
      <pre className="overflow-x-auto rounded-b-md bg-[color:var(--color-bg-soft)]">
        <code
          className="hljs block p-3 text-[12px] leading-relaxed"
          dangerouslySetInnerHTML={{ __html: highlight(code, language) }}
        />
      </pre>
    </div>
  );
}

/** 行内代码。 */
export function InlineCode({ children }: { children?: ReactNode }) {
  return (
    <code className="rounded bg-[color:var(--color-bg-soft)] px-1.5 py-0.5 font-[family-name:var(--font-mono)] text-[12px] text-[color:var(--color-up)]">
      {children}
    </code>
  );
}
