"use client";

import MarkdownView from "@/components/shared/MarkdownView";
import { confirmReport, type ConfirmAction } from "@/lib/reportApi";
import type { ReportDetailView } from "@/lib/reportSchemas";
import AnchorStateBadge from "./AnchorStateBadge";
import ReportConfirmCard from "./ReportConfirmCard";

// 研报详情（设计规格 §4.1）：正文 MarkdownView（复用 shared）+ 关键假设列表（逐条
// AnchorStateBadge 四态徽标，浮层带工具/参数/数据时点锚定真相 vs 现值重查对照）+
// limitations 注记块 + 确认卡。确认/拒绝调 confirmReport 后回调 onConfirmed，由容器
// 刷新列表与详情（状态即时刷新）；卡片自身状态由刷新后的 detail.status 驱动。

export default function ReportDetail({ detail, onConfirmed }: {
  detail: ReportDetailView;
  /** 确认/拒绝成功后回调（容器刷新列表与本详情）。 */
  onConfirmed: () => void | Promise<void>;
}) {
  const submit = (action: ConfirmAction, reason?: string) =>
    confirmReport(detail.id, action, reason).then(() => onConfirmed());

  return (
    <div
      data-testid={`report-detail-${detail.id}`}
      className="space-y-4 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)]/60 p-4"
    >
      <div data-testid="report-content">
        <MarkdownView content={detail.contentMd} />
      </div>

      <div className="space-y-2">
        <div className="flex flex-wrap items-center gap-2">
          <h4 className="text-sm font-medium text-[color:var(--color-ink)]">关键假设</h4>
          <span className="text-xs text-[color:var(--color-ink-faint)]">
            锚点重查于 {detail.stalenessCheckedAt.slice(0, 10)}
          </span>
        </div>
        <ul data-testid="report-assumptions" className="space-y-1.5">
          {detail.assumptions.map((a) => (
            <li
              key={a.seq}
              data-testid={`report-assumption-${a.seq}`}
              data-anchor-state={a.anchor?.state ?? "NONE"}
              className="flex flex-wrap items-center gap-2 text-sm"
            >
              {a.anchor ? (
                <AnchorStateBadge anchor={a.anchor} seq={a.seq} />
              ) : (
                // §3.7：已出稿研报假设必绑定锚点（门拦），null 为防御性兜底展示
                <span className="shrink-0 rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-faint)]">
                  未绑定锚点
                </span>
              )}
              <span className="min-w-0 flex-1 text-[color:var(--color-ink-dim)]">
                {a.seq}. {a.statement}
              </span>
            </li>
          ))}
        </ul>
      </div>

      {detail.limitations && (
        <div
          data-testid="report-limitations"
          role="note"
          className="rounded-lg border border-[color:var(--color-warn)]/40 bg-[color:var(--color-panel-2)] px-3 py-2 text-xs leading-relaxed text-[color:var(--color-ink-dim)]"
        >
          局限注记：{detail.limitations}
        </div>
      )}

      <ReportConfirmCard status={detail.status} onSubmit={submit} />
    </div>
  );
}
