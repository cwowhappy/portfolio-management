// 四阶段三态进度（D22）：完成度读模型由后端 StageCompletionService 唯一计算（S6/NFR-1），
// 本组件只忠实呈现——三态文案 + 完成方式角标（自动/手动），不做百分比。
// 缺键阶段按「未开始」兜底（zod record 不强制枚举键齐全，服务端 EnumMap 恒给四键）。

import {
  RESEARCH_STAGES,
  RESEARCH_STAGE_LABELS,
  STAGE_STATUS_LABELS,
  type CompletionBasis,
  type ResearchStage,
  type StageCompletion,
  type StageStatus,
} from "@/lib/researchSchemas";

const STATUS_DOT: Record<StageStatus, string> = {
  NOT_STARTED: "bg-[color:var(--color-ink-faint)]",
  IN_PROGRESS: "bg-[color:var(--color-accent)]",
  COMPLETED: "bg-[color:var(--color-up)]",
};

/** 完成角标：仅完成态有意义（AUTO=产物齐套 / MANUAL=手动兜底），PENDING 无角标。 */
function BasisBadge({ basis }: { basis: CompletionBasis }) {
  if (basis !== "AUTO" && basis !== "MANUAL") return null;
  return (
    <span
      data-badge={basis}
      className="mt-1.5 inline-block rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-dim)]"
    >
      {basis === "AUTO" ? "自动" : "手动"}
    </span>
  );
}

export default function StageProgress({ completions }: {
  // zod record 不保证枚举键齐全（运行时缺键按「未开始」兜底），类型如实标注 Partial
  completions: Partial<Record<ResearchStage, StageCompletion>>;
}) {
  return (
    <ol
      data-testid="stage-progress"
      className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4"
      aria-label="四阶段完成度"
    >
      {RESEARCH_STAGES.map((stage) => {
        const c = completions[stage] ?? { stage, status: "NOT_STARTED" as const, basis: "PENDING" as const };
        return (
          <li
            key={stage}
            data-stage={stage}
            className="rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 px-3.5 py-3"
          >
            <div className="flex items-center gap-2">
              <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${STATUS_DOT[c.status]}`} aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate text-sm text-[color:var(--color-ink)]">
                {RESEARCH_STAGE_LABELS[stage]}
              </span>
              <span className="shrink-0 text-xs text-[color:var(--color-ink-dim)]" data-status={c.status}>
                {STAGE_STATUS_LABELS[c.status]}
              </span>
            </div>
            <BasisBadge basis={c.basis} />
          </li>
        );
      })}
    </ol>
  );
}
