"use client";
// 研究项目详情页：六分区——①项目信息（改标题/切阶段/归档）②四阶段三态完成度 + 手动标记
// （D16 兜底）③投资策略面板（D13 两级状态机 + D10 证伪条件集）④建仓计划 + 纪律检查
// （F09/F10/F12，D18 页面级确认流）⑤证伪命中（D21 实时判定 + 历史留痕）⑥关联记录
// （F08 反查 journal RESEARCH_EVENT 与 wiki 研究笔记）。完成度为后端唯一计算点读模型（S6/NFR-1）。

import { use, useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { RequireAuth } from "@/components/auth/RequireAuth";
import EntryPlanPanel from "@/components/research/EntryPlanPanel";
import FalsifierPanel from "@/components/research/FalsifierPanel";
import StageProgress from "@/components/research/StageProgress";
import StrategyPanel from "@/components/research/StrategyPanel";
import { archiveProject, getLinkedNotes, getLinkedWiki, getProject, patchProject } from "@/lib/researchApi";
import { JOURNAL_ENTRY_TYPE_LABELS } from "@/lib/journalApi";
import {
  MANUAL_STATE_LABELS,
  PROJECT_STATUS_LABELS,
  RESEARCH_STAGES,
  RESEARCH_STAGE_LABELS,
  type ManualState,
  type ProjectDetailView,
  type ResearchStage,
} from "@/lib/researchSchemas";
import type { JournalEntryView, WikiEntryView } from "@/lib/types";

export default function ProjectDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return (
    <RequireAuth>
      <ProjectDetail projectId={Number(id)} />
    </RequireAuth>
  );
}

function ProjectDetail({ projectId }: { projectId: number }) {
  const [detail, setDetail] = useState<ProjectDetailView | null>(null);
  const [notes, setNotes] = useState<JournalEntryView[]>([]);
  const [wiki, setWiki] = useState<WikiEntryView[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  // 项目信息编辑
  const [titleDraft, setTitleDraft] = useState("");
  const [stageDraft, setStageDraft] = useState<ResearchStage>("NEW_ANALYSIS");
  const [markStage, setMarkStage] = useState<ResearchStage>("NEW_ANALYSIS");
  const [actionError, setActionError] = useState<string | null>(null);
  const [acting, setActing] = useState(false);

  const validId = Number.isInteger(projectId) && projectId > 0;

  // 非法 id 不发请求（渲染层兜底错误视图）；effect 内不做同步 setState（react-hooks 规则）
  const load = useCallback(() => {
    Promise.all([getProject(projectId), getLinkedNotes(projectId), getLinkedWiki(projectId)])
      .then(([d, n, w]) => {
        setDetail(d);
        setNotes(n);
        setWiki(w);
        setError(null);
      })
      .catch((err) => setError(err instanceof Error ? err.message : "加载失败"))
      .finally(() => setLoading(false));
  }, [projectId]);

  useEffect(() => {
    if (!validId) return;
    load();
  }, [validId, load]);

  // 读模型变更（含 PATCH 返回的 detail 直接 setDetail）→ 渲染期同步表单草稿
  //（照 IndustryStockTable 排序守卫写法，避免 effect 内同步 setState）
  const [prevDetail, setPrevDetail] = useState(detail);
  if (detail !== prevDetail) {
    setPrevDetail(detail);
    if (detail) {
      setTitleDraft(detail.project.title);
      setStageDraft(detail.project.currentStage);
    }
  }

  const runAction = async (action: () => Promise<unknown>) => {
    if (acting) return;
    setActing(true);
    setActionError(null);
    try {
      await action();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : "操作失败");
    } finally {
      setActing(false);
    }
  };

  const saveTitle = () =>
    void runAction(async () => {
      const trimmed = titleDraft.trim();
      if (!trimmed) throw new Error("项目标题不能为空");
      setDetail(await patchProject(projectId, { title: trimmed }));
    });

  const changeStage = () =>
    void runAction(async () => {
      setDetail(await patchProject(projectId, { currentStage: stageDraft }));
    });

  const manualMark = (state: ManualState) =>
    void runAction(async () => {
      setDetail(await patchProject(projectId, { manualMarks: [{ stage: markStage, state }] }));
    });

  const doArchive = () => {
    if (!confirm("归档该研究项目？")) return;
    void runAction(async () => {
      await archiveProject(projectId);
      load();
    });
  };

  // 非法路由 id（如 /research/new 兜底之外的乱输入）直接出错误视图，不发请求
  if (!validId) {
    return notFoundView();
  }
  if (loading) {
    return (
      <div className="mx-auto max-w-6xl px-6 py-8">
        <div className="h-40 rounded-2xl skeleton" aria-label="加载中" />
      </div>
    );
  }
  if (error || !detail) {
    return notFoundView(error);
  }

  const { project } = detail;

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6" data-testid="research-detail">
      <div className="flex flex-wrap items-center gap-3">
        <Link href="/research" className="text-sm text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-ink-dim)]">
          ← 研究项目
        </Link>
        <h1 className="font-[family-name:var(--font-display)] text-2xl">{project.title}</h1>
        <span className="rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-dim)]">
          {PROJECT_STATUS_LABELS[project.status]}
        </span>
      </div>

      {/* ① 项目信息 */}
      <section className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
        <h2 className="font-[family-name:var(--font-display)] text-[15px]">项目信息</h2>
        <div className="flex flex-wrap gap-x-6 gap-y-1 text-sm text-[color:var(--color-ink-dim)]">
          <span>标的：{project.stockName}（{project.stockCode}）</span>
          {project.industryCode && <span>行业：{project.industryCode}</span>}
          <span>创建于 {project.createdAt.slice(0, 10)}</span>
          <span>更新于 {project.updatedAt.slice(0, 10)}</span>
        </div>
        <div className="flex flex-wrap items-end gap-2">
          <div className="min-w-56">
            <label className="block text-xs text-[color:var(--color-ink-faint)] mb-1" htmlFor="project-title">项目标题</label>
            <input
              id="project-title" aria-label="项目标题"
              className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
              value={titleDraft}
              onChange={(e) => setTitleDraft(e.target.value)}
            />
          </div>
          <button type="button" className={btnGhost} disabled={acting} onClick={saveTitle}>保存标题</button>
          <div>
            <label className="block text-xs text-[color:var(--color-ink-faint)] mb-1" htmlFor="project-stage">当前阶段</label>
            <select
              id="project-stage" aria-label="当前阶段"
              className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
              value={stageDraft}
              onChange={(e) => setStageDraft(e.target.value as ResearchStage)}
            >
              {RESEARCH_STAGES.map((s) => (
                <option key={s} value={s}>{RESEARCH_STAGE_LABELS[s]}</option>
              ))}
            </select>
          </div>
          <button
            type="button" className={btnGhost} disabled={acting || stageDraft === project.currentStage}
            onClick={changeStage}
          >
            切换阶段
          </button>
          {project.status === "ACTIVE" && (
            <button
              type="button"
              className="rounded-md border border-[color:var(--color-down)]/50 px-3 py-1.5 text-sm text-[color:var(--color-down)] disabled:opacity-60"
              disabled={acting}
              onClick={doArchive}
            >
              归档项目
            </button>
          )}
        </div>
        {actionError && <div className="text-sm text-[color:var(--color-down)]">{actionError}</div>}
      </section>

      {/* ② 四阶段完成度 + 手动标记 */}
      <section className="space-y-3">
        <h2 className="font-[family-name:var(--font-display)] text-[15px]">四阶段完成度</h2>
        <StageProgress completions={detail.completions} />
        <div className="flex flex-wrap items-end gap-2 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-4">
          <div>
            <label className="block text-xs text-[color:var(--color-ink-faint)] mb-1" htmlFor="mark-stage">手动标记阶段</label>
            <select
              id="mark-stage" aria-label="手动标记阶段"
              className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
              value={markStage}
              onChange={(e) => setMarkStage(e.target.value as ResearchStage)}
            >
              {RESEARCH_STAGES.map((s) => (
                <option key={s} value={s}>{RESEARCH_STAGE_LABELS[s]}</option>
              ))}
            </select>
          </div>
          <button type="button" className={btnGhost} disabled={acting} onClick={() => manualMark("COMPLETED")}>
            {MANUAL_STATE_LABELS.COMPLETED}
          </button>
          <button type="button" className={btnGhost} disabled={acting} onClick={() => manualMark("REOPENED")}>
            {MANUAL_STATE_LABELS.REOPENED}
          </button>
          <span className="text-xs text-[color:var(--color-ink-faint)]">
            手动标记为兜底（D16）：完成可无产物，重开覆盖自动判定
          </span>
        </div>
      </section>

      {/* ③ 投资策略 + 证伪条件 */}
      <StrategyPanel
        projectId={projectId}
        strategy={detail.strategy}
        falsifiers={detail.falsifiers}
        onChanged={load}
      />

      {/* ④ 建仓计划 + 纪律检查（D18：检查从批次/卖出意图主动发起，不经 portfolio 交易页） */}
      <EntryPlanPanel projectId={projectId} onChanged={load} />

      {/* ⑤ 证伪命中（D21 实时判定 + 历史留痕，评审入口 P4） */}
      <FalsifierPanel projectId={projectId} />

      {/* ⑥ 关联记录（F08 反查） */}
      <section className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-4">
        <h2 className="font-[family-name:var(--font-display)] text-[15px]">关联记录</h2>
        <div>
          <h3 className="mb-2 text-sm font-medium text-[color:var(--color-ink)]">决策记录（journal）</h3>
          {notes.length === 0 ? (
            <p className="text-sm text-[color:var(--color-ink-faint)]">
              暂无关联记录——立项/阶段变更/定稿等研究事件会自动写入这里。
            </p>
          ) : (
            <ul className="space-y-1.5" data-testid="linked-notes">
              {notes.map((n) => (
                <li key={n.id} className="flex gap-2 text-sm">
                  <span className="w-20 shrink-0 text-xs text-[color:var(--color-ink-faint)]">{n.eventDate}</span>
                  <span className="w-16 shrink-0 text-xs">{JOURNAL_ENTRY_TYPE_LABELS[n.type]}</span>
                  <span className="min-w-0 truncate text-[color:var(--color-ink-dim)]">{n.title}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
        <div>
          <h3 className="mb-2 text-sm font-medium text-[color:var(--color-ink)]">知识库条目（wiki）</h3>
          {wiki.length === 0 ? (
            <p className="text-sm text-[color:var(--color-ink-faint)]">暂无关联知识库条目。</p>
          ) : (
            <ul className="space-y-1.5" data-testid="linked-wiki">
              {wiki.map((w) => (
                <li key={w.id} className="flex gap-2 text-sm">
                  <span className="w-20 shrink-0 text-xs text-[color:var(--color-ink-faint)]">{w.updatedAt.slice(0, 10)}</span>
                  <span className="min-w-0 truncate text-[color:var(--color-ink-dim)]">{w.title}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </section>
    </div>
  );
}

const btnGhost =
  "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)] text-[color:var(--color-ink-dim)] disabled:opacity-60";

/** 404/加载失败兜底视图：非本人项目后端一律 404「研究项目不存在」，不泄漏存在性。 */
function notFoundView(message: string | null = null) {
  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-3" data-testid="research-detail-error">
      <p className="text-sm text-[color:var(--color-ink-dim)]">{message ?? "研究项目不存在"}</p>
      <Link href="/research" className="text-sm text-[color:var(--color-accent)] hover:underline">
        ← 返回研究项目列表
      </Link>
    </div>
  );
}
