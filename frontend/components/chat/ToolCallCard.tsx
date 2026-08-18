"use client";

import { useState } from "react";

export interface ToolCallState {
  toolCallId: string;
  name: string;
  args: string;
  result: string | null;
  status: "running" | "done" | "error";
}

const TOOL_LABELS: Record<string, string> = {
  search_stock: "搜索股票",
  get_quote: "实时行情",
  get_kline: "K线走势",
  get_financials: "财务指标",
  get_news: "个股新闻",
  get_market_overview: "大盘速览",
};

function labelOf(name: string) {
  return TOOL_LABELS[name] ?? name;
}

function prettyArgs(name: string, args: string): string {
  if (!args) return "";
  try {
    const obj = JSON.parse(args);
    const parts: string[] = [];
    if (obj.code) parts.push(String(obj.code));
    if (obj.query) parts.push(String(obj.query));
    if (obj.period) parts.push(String(obj.period));
    if (obj.limit) parts.push("近" + String(obj.limit) + "根");
    return parts.join(" · ");
  } catch {
    return args.slice(0, 60);
  }
}

export default function ToolCallCard({ tool }: { tool: ToolCallState }) {
  const [open, setOpen] = useState(false);
  const summary = prettyArgs(tool.name, tool.args);

  return (
    <div
      className={
        "tool-card my-2 w-full max-w-[560px] overflow-hidden " +
        (tool.status === "running" ? "running" : "")
      }
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        className="flex w-full items-center gap-2.5 px-3.5 py-2.5 text-left"
      >
        <span className="grid h-5 w-5 shrink-0 place-items-center rounded border border-[color:var(--color-line)] text-[10px] text-[color:var(--color-up)]">
          {tool.status === "running" ? <span className="tool-pulse" /> : "✓"}
        </span>
        <span className="text-[13px] text-[color:var(--color-ink)]">{labelOf(tool.name)}</span>
        {summary && (
          <span className="tabular truncate text-[12px] text-[color:var(--color-ink-faint)]">
            {summary}
          </span>
        )}
        <span
          className={
            "ml-auto text-[11px] transition-transform duration-200 " +
            (open ? "rotate-180 text-[color:var(--color-ink-dim)]" : "text-[color:var(--color-ink-faint)]")
          }
        >
          ▾
        </span>
      </button>
      {open && (
        <div className="border-t border-[color:var(--color-line-soft)] px-3.5 py-2.5">
          <pre className="max-h-56 overflow-auto whitespace-pre-wrap break-all font-[family-name:var(--font-mono)] text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
            {tool.result ?? (tool.status === "running" ? tool.args || "执行中…" : "无结果")}
          </pre>
        </div>
      )}
    </div>
  );
}
