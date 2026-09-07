"use client";

export interface InterruptApprovalCardProps {
  toolName: string;
  /** interrupt.metadata.toolInput（JSON-object 字符串或对象） */
  toolInput?: unknown;
  /** 兜底说明文案（interrupt.message） */
  message?: string;
  onApprove: () => void;
  onDeny: () => void;
}

/** 权限审批卡片（mcp-hitl FR-3）：Agent 调用写性质 MCP 工具时逐次审批。 */
export default function InterruptApprovalCard({
  toolName,
  toolInput,
  message,
  onApprove,
  onDeny,
}: InterruptApprovalCardProps) {
  const argsText =
    toolInput == null
      ? null
      : typeof toolInput === "string"
        ? toolInput
        : JSON.stringify(toolInput, null, 2);
  return (
    <div className="tool-card my-2 w-full max-w-[560px] px-3.5 py-2.5">
      <p className="text-[13px] text-[color:var(--color-ink)]">
        需要确认：<span className="font-medium">{toolName}</span>
      </p>
      {argsText != null && (
        <details className="mt-1.5">
          <summary className="cursor-pointer select-none text-[12px] text-[color:var(--color-ink-faint)]">
            查看调用参数
          </summary>
          <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-all font-[family-name:var(--font-mono)] text-[11px] leading-relaxed text-[color:var(--color-ink-dim)]">
            {argsText}
          </pre>
        </details>
      )}
      {message && (
        <p className="mt-1.5 text-[12px] text-[color:var(--color-ink-faint)]">{message}</p>
      )}
      <div className="mt-2.5 flex gap-2">
        <button
          type="button"
          onClick={onApprove}
          className="rounded-md border border-[color:var(--color-up)]/50 px-3 py-1 text-[12px] text-[color:var(--color-up)] hover:bg-[color:var(--color-up)]/10"
        >
          批准
        </button>
        <button
          type="button"
          onClick={onDeny}
          className="rounded-md border border-[color:var(--color-down)]/50 px-3 py-1 text-[12px] text-[color:var(--color-down)] hover:bg-[color:var(--color-down)]/10"
        >
          拒绝
        </button>
      </div>
    </div>
  );
}
