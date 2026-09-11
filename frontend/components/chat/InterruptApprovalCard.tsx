"use client";

import { useState } from "react";

export interface InterruptApprovalCardProps {
  toolName: string;
  /** interrupt.metadata.toolInput（JSON-object 字符串或对象） */
  toolInput?: unknown;
  /** 兜底说明文案（interrupt.message） */
  message?: string;
  /**
   * 已处理态外部覆盖（mcp-hitl FR-5 补全）：prop 有值时优先于卡片内部 state。默认不传——
   * useInterrupt 对 render 产物做元素 memo 且依赖不含宿主组件重渲染，宿主 setState 后 memo
   * 命中仍返回旧元素，父层 prop 传不进去（v1 真机失效根因），故已处理态默认由卡片内部
   * state 维护（见下），组件卸载即重置（interrupts 清空/新一轮新 key 重挂）。
   */
  decision?: "approved" | "denied";
  onApprove: () => void;
  onDeny: () => void;
}

/** 权限审批卡片（mcp-hitl FR-3）：Agent 调用写性质 MCP 工具时逐次审批。 */
export default function InterruptApprovalCard({
  toolName,
  toolInput,
  message,
  decision: decisionProp,
  onApprove,
  onDeny,
}: InterruptApprovalCardProps) {
  // 已处理态（FR-5 补全）：resolve 是 accumulate-then-submit，全部 open interrupt 应答后
  // 才提交 resume，期间卡片不会消失。状态放卡片内部：组件自身 setState 必然重渲染自身，
  // 不受 useInterrupt 元素 memo 影响（v2；v1 父层 handled 状态经真机验证传不进 memo 旧元素）。
  const [internalDecision, setInternalDecision] = useState<"approved" | "denied" | null>(null);
  const decision = decisionProp ?? internalDecision;
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
      {decision ? (
        // 已处理态：按钮区收起，仅一行状态文案（色系对齐原按钮——批准 up / 拒绝 down）
        <p
          className={
            "mt-2.5 text-[12px] " +
            (decision === "approved"
              ? "text-[color:var(--color-up)]"
              : "text-[color:var(--color-down)]")
          }
        >
          {decision === "approved" ? "已批准，等待其余确认…" : "已拒绝，等待其余确认…"}
        </p>
      ) : (
        <div className="mt-2.5 flex gap-2">
          <button
            type="button"
            onClick={() => {
              setInternalDecision("approved");
              onApprove();
            }}
            className="rounded-md border border-[color:var(--color-up)]/50 px-3 py-1 text-[12px] text-[color:var(--color-up)] hover:bg-[color:var(--color-up)]/10"
          >
            批准
          </button>
          <button
            type="button"
            onClick={() => {
              setInternalDecision("denied");
              onDeny();
            }}
            className="rounded-md border border-[color:var(--color-down)]/50 px-3 py-1 text-[12px] text-[color:var(--color-down)] hover:bg-[color:var(--color-down)]/10"
          >
            拒绝
          </button>
        </div>
      )}
    </div>
  );
}
