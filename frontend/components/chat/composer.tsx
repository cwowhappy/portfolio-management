"use client";
// 输入区（Composer）：自 ThreadArea 按节拆出（2026-10 P3 审查 F4，纯搬移零行为变更）。

import { useRef, useState } from "react";

export function Composer({
  isRunning,
  ready,
  onSend,
  onStop,
}: {
  isRunning: boolean;
  ready: boolean;
  onSend: (text: string) => void;
  onStop: () => void;
}) {
  const [draft, setDraft] = useState("");
  const taRef = useRef<HTMLTextAreaElement>(null);
  const submit = () => {
    const t = draft.trim();
    if (!t || isRunning || !ready) return;
    setDraft("");
    if (taRef.current) taRef.current.style.height = "auto"; // 重置自增高
    onSend(t);
  };
  return (
    <div className="border-t border-[color:var(--color-line)] bg-[color:var(--color-bg)]/80 px-4 pb-4 pt-3 backdrop-blur-sm">
      <div className="composer mx-auto max-w-[860px] p-2">
        <textarea
          ref={taRef}
          rows={1}
          autoFocus
          value={draft}
          placeholder="问行情、看走势、读财报… 例如：帮我看看贵州茅台最近的走势和估值"
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey) {
              // 中文 IME 组合中 Enter 用于选定候选词，不得发送（Shift+Enter 换行不受影响）
              if (e.nativeEvent.isComposing) return;
              e.preventDefault();
              submit();
            }
          }}
          onInput={(e) => {
            const el = e.currentTarget;
            el.style.height = "auto";
            el.style.height = Math.min(el.scrollHeight, 160) + "px";
          }}
          className="max-h-40 w-full resize-none bg-transparent px-2.5 py-1.5 text-[14px] leading-relaxed text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:outline-none"
        />
        <div className="flex items-center justify-between px-1.5 pb-0.5 pt-1.5">
          <span className="text-[11px] text-[color:var(--color-ink-faint)]">
            Enter 发送 · Shift+Enter 换行 · 回答由 DeepSeek 生成
          </span>
          {isRunning ? (
            <button
              onClick={onStop}
              className="rounded-md border border-[color:var(--color-line)] px-3 py-1 text-[12px] text-[color:var(--color-ink-dim)] transition-colors hover:border-[color:var(--color-up)] hover:text-[color:var(--color-up)]"
            >
              ■ 停止
            </button>
          ) : (
            <button
              onClick={submit}
              disabled={!ready || !draft.trim()}
              className="rounded-md bg-[color:var(--color-up)] px-4 py-1 text-[12px] text-white transition-all enabled:hover:brightness-110 disabled:opacity-30"
            >
              发送
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
