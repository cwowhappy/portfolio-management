"use client";
// 反馈条：自 ThreadArea 按节拆出（2026-10 P3 审查 F4，纯搬移零行为变更）。

import { useEffect, useState } from "react";

// localStorage 反馈键容量上限：超出时清最旧，避免无限增长
const FEEDBACK_KEY_PREFIX = "invest.feedback.";
const FEEDBACK_MAX_ENTRIES = 200;

/** 反馈键数量达到上限时，按写入时间清掉最旧的，腾出位置给新反馈。 */
function pruneFeedbackKeys() {
  const entries: { key: string; at: number }[] = [];
  for (let i = 0; i < localStorage.length; i++) {
    const key = localStorage.key(i);
    if (!key?.startsWith(FEEDBACK_KEY_PREFIX)) continue;
    let at = 0;
    try {
      at = (JSON.parse(localStorage.getItem(key) ?? "{}") as { at?: number }).at ?? 0;
    } catch {
      // 损坏数据视为最旧，优先清掉
    }
    entries.push({ key, at });
  }
  entries.sort((a, b) => a.at - b.at);
  while (entries.length >= FEEDBACK_MAX_ENTRIES) {
    const oldest = entries.shift();
    if (oldest) localStorage.removeItem(oldest.key);
  }
}

export function FeedbackBar({ messageId }: { messageId: string }) {
  const [voted, setVoted] = useState<"positive" | "negative" | null>(null);
  useEffect(() => {
    // localStorage 仅客户端可读，必须挂载后恢复；微任务 defer 避免 effect 体内同步 setState
    // （同步读会破坏服务端/客户端首帧一致性，见规范 4.3）
    let cancelled = false;
    void Promise.resolve().then(() => {
      if (cancelled) return;
      try {
        const raw = localStorage.getItem(FEEDBACK_KEY_PREFIX + messageId);
        if (raw) setVoted((JSON.parse(raw) as { type: "positive" | "negative" }).type);
      } catch {
        // ignore
      }
    });
    return () => {
      cancelled = true;
    };
  }, [messageId]);
  const vote = (type: "positive" | "negative") => {
    try {
      pruneFeedbackKeys();
      localStorage.setItem(
        FEEDBACK_KEY_PREFIX + messageId,
        JSON.stringify({ type, at: Date.now() }),
      );
    } catch {
      // ignore
    }
    setVoted(type);
  };
  return (
    <div className="mt-1.5 flex items-center gap-1 opacity-35 transition-opacity hover:opacity-100">
      <button
        aria-label="回答有帮助"
        aria-pressed={voted === "positive"}
        onClick={() => vote("positive")}
        className={
          "rounded px-1.5 py-0.5 text-[12px] transition-colors " +
          (voted === "positive" ? "text-[color:var(--color-down)]" : "text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-down)]")
        }
      >
        👍
      </button>
      <button
        aria-label="回答需要改进"
        aria-pressed={voted === "negative"}
        onClick={() => vote("negative")}
        className={
          "rounded px-1.5 py-0.5 text-[12px] transition-colors " +
          (voted === "negative" ? "text-[color:var(--color-up)]" : "text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-up)]")
        }
      >
        👎
      </button>
    </div>
  );
}
