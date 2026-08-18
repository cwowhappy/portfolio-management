"use client";

import { useRef, useState } from "react";

export default function Composer({
  disabled,
  streaming,
  onSend,
  onStop,
}: {
  disabled: boolean;
  streaming: boolean;
  onSend: (text: string) => void;
  onStop: () => void;
}) {
  const [text, setText] = useState("");
  const ref = useRef<HTMLTextAreaElement>(null);

  const submit = () => {
    const t = text.trim();
    if (!t || disabled) return;
    onSend(t);
    setText("");
    if (ref.current) ref.current.style.height = "auto";
  };

  return (
    <div className="border-t border-[color:var(--color-line)] bg-[color:var(--color-bg)]/80 px-4 pb-4 pt-3 backdrop-blur-sm">
      <div className="composer mx-auto max-w-[860px] p-2">
        <textarea
          ref={ref}
          value={text}
          rows={1}
          placeholder="问行情、看走势、读财报… 例如：帮我看看贵州茅台最近的走势和估值"
          disabled={disabled}
          onChange={(e) => {
            setText(e.target.value);
            e.target.style.height = "auto";
            e.target.style.height = Math.min(e.target.scrollHeight, 160) + "px";
          }}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
              e.preventDefault();
              submit();
            }
          }}
          className="max-h-40 w-full resize-none bg-transparent px-2.5 py-1.5 text-[14px] leading-relaxed text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:outline-none"
        />
        <div className="flex items-center justify-between px-1.5 pb-0.5 pt-1.5">
          <span className="text-[11px] text-[color:var(--color-ink-faint)]">
            Enter 发送 · Shift+Enter 换行 · 回答由 DeepSeek 生成
          </span>
          {streaming ? (
            <button
              type="button"
              onClick={onStop}
              className="rounded-md border border-[color:var(--color-line)] px-3 py-1 text-[12px] text-[color:var(--color-ink-dim)] transition-colors hover:border-[color:var(--color-up)] hover:text-[color:var(--color-up)]"
            >
              ■ 停止
            </button>
          ) : (
            <button
              type="button"
              onClick={submit}
              disabled={disabled || !text.trim()}
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
