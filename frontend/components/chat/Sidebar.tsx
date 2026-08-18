"use client";

import type { SessionMeta } from "@/lib/sessions";

function timeAgo(ts: number): string {
  const diff = Date.now() - ts;
  const min = Math.floor(diff / 60000);
  if (min < 1) return "刚刚";
  if (min < 60) return min + " 分钟前";
  const h = Math.floor(min / 60);
  if (h < 24) return h + " 小时前";
  return Math.floor(h / 24) + " 天前";
}

export default function Sidebar({
  sessions,
  activeId,
  health,
  onSelect,
  onNew,
  onDelete,
}: {
  sessions: SessionMeta[];
  activeId: string | null;
  health: { llmKey: boolean; marketOk: boolean } | null;
  onSelect: (id: string) => void;
  onNew: () => void;
  onDelete: (id: string) => void;
}) {
  return (
    <aside className="flex h-full w-64 shrink-0 flex-col border-r border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)]/60">
      <div className="p-3">
        <button
          type="button"
          onClick={onNew}
          className="w-full rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-3 py-2 text-[13px] text-[color:var(--color-ink)] transition-all hover:border-[color:var(--color-up)] hover:shadow-[var(--shadow-glow)]"
        >
          ＋ 新对话
        </button>
      </div>

      <nav className="flex-1 overflow-y-auto px-2 pb-3">
        {sessions.length === 0 && (
          <p className="px-3 pt-8 text-center text-[12px] leading-relaxed text-[color:var(--color-ink-faint)]">
            还没有会话
            <br />
            从一句提问开始吧
          </p>
        )}
        <ul className="space-y-0.5">
          {sessions.map((s) => (
            <li key={s.id} className="group relative">
              <button
                type="button"
                onClick={() => onSelect(s.id)}
                className={
                  "w-full rounded-lg px-3 py-2 text-left transition-colors " +
                  (s.id === activeId
                    ? "bg-[color:var(--color-panel-2)] text-[color:var(--color-ink)]"
                    : "text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)] hover:text-[color:var(--color-ink)]")
                }
              >
                <span className="block truncate text-[13px]">{s.title}</span>
                <span className="mt-0.5 block text-[11px] text-[color:var(--color-ink-faint)]">
                  {timeAgo(s.updatedAt)}
                </span>
              </button>
              <button
                type="button"
                aria-label="删除会话"
                onClick={() => onDelete(s.id)}
                className="absolute right-1.5 top-1/2 hidden -translate-y-1/2 rounded p-1 text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-up)] group-hover:block"
              >
                ✕
              </button>
            </li>
          ))}
        </ul>
      </nav>

      <footer className="border-t border-[color:var(--color-line-soft)] p-3 text-[11px] leading-relaxed text-[color:var(--color-ink-faint)]">
        <div className="mb-2 flex items-center gap-2">
          <span
            className={
              "h-1.5 w-1.5 rounded-full " +
              (health
                ? health.llmKey && health.marketOk
                  ? "bg-[color:var(--color-down)]"
                  : "bg-[color:var(--color-amber)]"
                : "bg-[color:var(--color-ink-faint)]")
            }
          />
          <span>
            {health
              ? health.llmKey && health.marketOk
                ? "系统就绪"
                : health.llmKey
                  ? "行情源异常"
                  : "未配置模型 Key"
              : "连接中…"}
          </span>
        </div>
        <p>数据来自公开行情接口，仅供参考，不构成投资建议。</p>
      </footer>
    </aside>
  );
}
