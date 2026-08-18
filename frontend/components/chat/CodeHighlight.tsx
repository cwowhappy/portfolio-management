"use client";

import { useState } from "react";
import hljs from "highlight.js/lib/common";
import type {
  CodeHeaderProps,
  SyntaxHighlighterProps,
} from "@assistant-ui/react-markdown";

/** 代码块头：语言标签 + 复制按钮。 */
export function CodeHeader({ language, code }: CodeHeaderProps) {
  const [copied, setCopied] = useState(false);
  const copy = () => {
    void navigator.clipboard?.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };
  return (
    <div className="flex items-center justify-between rounded-t-md border border-b-0 border-[color:var(--color-line-soft)] bg-[color:var(--color-panel-2)] px-3 py-1.5">
      <span className="font-[family-name:var(--font-mono)] text-[11px] uppercase tracking-wider text-[color:var(--color-ink-faint)]">
        {language || "text"}
      </span>
      <button
        type="button"
        onClick={copy}
        className="rounded px-2 py-0.5 text-[11px] text-[color:var(--color-ink-dim)] transition-colors hover:text-[color:var(--color-up)]"
      >
        {copied ? "已复制" : "复制"}
      </button>
    </div>
  );
}

/** 代码块高亮（highlight.js core + 常用语言）。 */
export function SyntaxHighlighter({
  components: { Pre, Code },
  language,
  code,
}: SyntaxHighlighterProps) {
  let html: string;
  try {
    html = hljs.highlight(code, {
      language: hljs.getLanguage(language) ? language : "plaintext",
    }).value;
  } catch {
    html = code
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;");
  }
  return (
    <Pre className="overflow-x-auto rounded-b-md bg-[color:var(--color-bg-soft)]">
      <Code
        className="hljs block p-3 text-[12px] leading-relaxed"
        dangerouslySetInnerHTML={{ __html: html }}
      />
    </Pre>
  );
}
