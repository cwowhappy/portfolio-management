"use client";
// 研究项目列表页（F07）：阶段/状态过滤 + 标的搜索 + 空态引导（筛选器/行业中心「发起研究」入口）。
// 完成度不在列表重复计算（S6/NFR-1）——三态进度只在详情页呈现后端读模型。

import { useCallback, useEffect, useRef, useState } from "react";
import Link from "next/link";
import { RequireAuth } from "@/components/auth/RequireAuth";
import ProjectList from "@/components/research/ProjectList";
import { archiveProject, listProjects } from "@/lib/researchApi";
import {
  PROJECT_STATUS_LABELS,
  RESEARCH_STAGES,
  RESEARCH_STAGE_LABELS,
  type ProjectStatus,
  type ProjectView,
  type ResearchStage,
} from "@/lib/researchSchemas";

type StageFilter = ResearchStage | "ALL";

export default function ResearchPage() {
  return (
    <RequireAuth>
      <ResearchBoard />
    </RequireAuth>
  );
}

function ResearchBoard() {
  const [stage, setStage] = useState<StageFilter>("ALL");
  const [status, setStatus] = useState<ProjectStatus>("ACTIVE");
  const [q, setQ] = useState("");
  const [projects, setProjects] = useState<ProjectView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    // 注意：不在启动时同步 setLoading(true)（react-hooks/set-state-in-effect）——
    // loading 只用于首屏骨架，过滤变更时旧列表原地刷新（seq 守卫丢弃过期响应）
    listProjects({ stage: stage === "ALL" ? undefined : stage, status, q: q.trim() || undefined })
      .then((list) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的 reload，丢弃过期响应
        setProjects(list);
        setError(null);
      })
      .catch((err) => {
        if (seq !== requestSeqRef.current) return;
        setError(err instanceof Error ? err.message : "加载失败");
      })
      .finally(() => {
        if (seq === requestSeqRef.current) setLoading(false);
      });
  }, [stage, status, q]);

  useEffect(() => { reload(); }, [reload]);

  const onArchive = (id: number) => {
    if (!confirm("归档该研究项目？归档后可在「已归档」中查看。")) return;
    archiveProject(id)
      .then(reload)
      .catch((err) => setError(err instanceof Error ? err.message : "归档失败"));
  };

  const filtered = stage !== "ALL" || q.trim() !== "" || status !== "ACTIVE";

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6" data-testid="research-board">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">研究项目</h1>
        <Link
          href="/research/new"
          className="rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
        >
          发起研究
        </Link>
      </div>

      <div className="flex flex-wrap items-center gap-2" data-testid="research-filters">
        <button
          type="button"
          className={stage === "ALL" ? filterActive : filterInactive}
          onClick={() => setStage("ALL")}
        >
          全部阶段
        </button>
        {RESEARCH_STAGES.map((s) => (
          <button
            key={s}
            type="button"
            className={stage === s ? filterActive : filterInactive}
            onClick={() => setStage(s)}
          >
            {RESEARCH_STAGE_LABELS[s]}
          </button>
        ))}
        <span className="mx-1 h-4 w-px bg-[color:var(--color-line)]" aria-hidden="true" />
        {(["ACTIVE", "ARCHIVED"] as ProjectStatus[]).map((s) => (
          <button
            key={s}
            type="button"
            className={status === s ? filterActive : filterInactive}
            onClick={() => setStatus(s)}
          >
            {PROJECT_STATUS_LABELS[s]}
          </button>
        ))}
        <input
          type="search"
          aria-label="搜索研究项目"
          placeholder="搜索标题 / 标的"
          className="ml-auto rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
          value={q}
          onChange={(e) => setQ(e.target.value)}
        />
      </div>

      {error && (
        <div className="rounded-xl border border-[color:var(--color-line)] p-5 text-sm text-[color:var(--color-ink-dim)]">
          加载失败：{error}
        </div>
      )}

      {loading ? (
        <div className="h-24 rounded-2xl skeleton" aria-label="加载中" />
      ) : projects.length === 0 ? (
        filtered ? (
          <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-6 text-sm text-[color:var(--color-ink-faint)]" data-testid="research-empty-filtered">
            没有符合条件的研究项目
            <button
              type="button"
              className="ml-3 rounded-md border border-[color:var(--color-line)] px-2.5 py-1 text-xs text-[color:var(--color-ink-dim)]"
              onClick={() => { setStage("ALL"); setStatus("ACTIVE"); setQ(""); }}
            >
              清除过滤
            </button>
          </div>
        ) : (
          <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-8 text-center space-y-3" data-testid="research-empty">
            <p className="text-sm text-[color:var(--color-ink-dim)]">
              还没有研究项目——从筛选器或行业中心对标的点「发起研究」，或直接立项开始新分析。
            </p>
            <div className="flex items-center justify-center gap-3">
              <Link
                href="/research/new"
                className="rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]"
              >
                立项发起研究
              </Link>
              <Link
                href="/screener"
                className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm text-[color:var(--color-ink-dim)]"
              >
                去筛选器找标的
              </Link>
            </div>
          </div>
        )
      ) : (
        <ProjectList projects={projects} onArchive={onArchive} />
      )}
    </div>
  );
}

const filterActive = "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]";
const filterInactive = "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]";
