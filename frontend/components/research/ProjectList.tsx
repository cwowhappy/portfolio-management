"use client";

import Link from "next/link";
import {
  PROJECT_STATUS_LABELS,
  RESEARCH_STAGE_LABELS,
  type ProjectView,
} from "@/lib/researchSchemas";

// 研究项目列表行（F07）：标题进详情、标的/阶段/状态速览；归档动作回抛页面统一处理。
// 空列表不在本组件渲染（页面对「全空引导」与「过滤无结果」分开展示）。

export default function ProjectList({ projects, onArchive }: {
  projects: ProjectView[];
  onArchive: (id: number) => void;
}) {
  if (projects.length === 0) return null;
  return (
    <ul className="space-y-2" data-testid="project-list">
      {projects.map((p) => (
        <li
          key={p.id}
          className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 px-4 py-3"
        >
          <Link
            href={`/research/${p.id}`}
            className="text-sm font-medium text-[color:var(--color-ink)] hover:text-[color:var(--color-accent)]"
          >
            {p.title}
          </Link>
          <span className="text-xs text-[color:var(--color-ink-dim)]">
            {p.stockName} {p.stockCode}
          </span>
          {p.industryCode && (
            <span className="rounded border border-[color:var(--color-line-soft)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-faint)]">
              {p.industryCode}
            </span>
          )}
          <span className="rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-dim)]">
            {RESEARCH_STAGE_LABELS[p.currentStage]}
          </span>
          <span className="text-[11px] text-[color:var(--color-ink-faint)]">
            {PROJECT_STATUS_LABELS[p.status]}
          </span>
          <span className="ml-auto text-[11px] text-[color:var(--color-ink-faint)]">
            更新 {p.updatedAt.slice(0, 10)}
          </span>
          {p.status === "ACTIVE" && (
            <button
              type="button"
              onClick={() => onArchive(p.id)}
              className="rounded-md border border-[color:var(--color-line)] px-2 py-1 text-xs text-[color:var(--color-ink-dim)] hover:border-[color:var(--color-ink-faint)]"
              aria-label={`归档 ${p.title}`}
            >
              归档
            </button>
          )}
        </li>
      ))}
    </ul>
  );
}
