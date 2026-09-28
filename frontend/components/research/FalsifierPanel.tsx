"use client";

import { useCallback, useEffect, useState } from "react";
import { getHits } from "@/lib/researchApi";
import {
  FALSIFIER_KIND_LABELS,
  FALSIFIER_PREDICATE_LABELS,
  type FalsifierHitView,
} from "@/lib/researchSchemas";

// 证伪命中合并视图面板（D21 页面实时判定 + 历史留痕；评审入口 P4 接）。
// 两条展示裁定（T4 审查产出，必须遵守）：
// 1. EVENT 条目 hit 恒 false——按 basis（「已确认事件」/「待人工勾选」）分组展示，不读 hit（Ruling-18）；
// 2. 历史行（realtime=false）hit=false 自述误导——给「历史命中」标签（历史=已发生命中），不读 hit。
// 条件现态由行内 kind/predicate/threshold/note 呈现；条件已整替删除的历史行现态为 null，
// 仅保 basis 与时间（后端 historyView 契约）。

type Tone = "hit" | "ok" | "faint";

const badgeCls = (tone: Tone) =>
  `rounded border px-1.5 py-0.5 text-[11px] shrink-0 ${
    tone === "hit"
      ? "border-[color:var(--color-down)]/50 text-[color:var(--color-down)]"
      : tone === "ok"
        ? "border-[color:var(--color-up)]/50 text-[color:var(--color-up)]"
        : "border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]"
  }`;

/** PREDICATE 实时行状态：hit=已命中 / skipped=无数据 / pending=待人工勾选 / 其余=未命中。 */
function predicateStatus(h: FalsifierHitView): { label: string; tone: Tone } {
  if (h.hit) return { label: "已命中", tone: "hit" };
  if (h.skipped) return { label: "无数据", tone: "faint" };
  if (h.pending) return { label: "待人工勾选", tone: "faint" };
  return { label: "未命中", tone: "ok" };
}

/** 条件描述：PREDICATE 用「谓词 阈值」，EVENT 用说明文字；现态缺失（已删条件）单列。 */
function conditionText(h: FalsifierHitView): string | null {
  if (h.kind === "PREDICATE" && h.predicate != null && h.threshold != null) {
    return `${FALSIFIER_PREDICATE_LABELS[h.predicate]} ${h.threshold}`;
  }
  if (h.kind === "EVENT") return h.note;
  return null;
}

function HitRow({ hit: h }: { hit: FalsifierHitView }) {
  const isEventRealtime = h.realtime && h.kind === "EVENT";
  const status = h.realtime
    ? isEventRealtime
      // Ruling-18：EVENT 不读 hit，按 eventChecked/basis 分组
      ? { label: h.eventChecked ? "已确认事件" : "待人工勾选", tone: (h.eventChecked ? "hit" : "faint") as Tone }
      : predicateStatus(h)
    : { label: "历史命中", tone: "hit" as Tone }; // 历史行不读 hit（恒 false 自述误导）

  const condition = conditionText(h);
  return (
    <li className="space-y-0.5">
      <div className="flex flex-wrap items-center gap-2 text-sm">
        {h.kind != null && (
          <span className="rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-dim)] shrink-0">
            {FALSIFIER_KIND_LABELS[h.kind]}
          </span>
        )}
        {condition != null ? (
          <span className="min-w-0 text-[color:var(--color-ink-dim)]">{condition}</span>
        ) : (
          <span className="text-xs text-[color:var(--color-ink-faint)]">已删除条件</span>
        )}
        {h.kind === "PREDICATE" && h.note != null && (
          <span className="min-w-0 text-xs text-[color:var(--color-ink-faint)]">{h.note}</span>
        )}
        {!h.realtime && h.hitAt != null && (
          <span className="text-xs text-[color:var(--color-ink-faint)]">{h.hitAt.slice(0, 10)}</span>
        )}
        <span className={badgeCls(status.tone)}>{status.label}</span>
      </div>
      {/* 实时 EVENT 行 basis 与徽标文案逐字相同（后端契约），不重复渲染 */}
      {h.basis != null && !isEventRealtime && (
        <p className="pl-1 text-xs leading-relaxed text-[color:var(--color-ink-faint)]">{h.basis}</p>
      )}
    </li>
  );
}

export default function FalsifierPanel({ projectId }: { projectId: number }) {
  const [hits, setHits] = useState<FalsifierHitView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(() => {
    getHits(projectId)
      .then((h) => {
        setHits(h);
        setError(null);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "加载失败"))
      .finally(() => setLoading(false));
  }, [projectId]);

  useEffect(() => {
    load();
  }, [load]);

  const realtime = (hits ?? []).filter((h) => h.realtime);
  const history = (hits ?? []).filter((h) => !h.realtime);

  return (
    <section className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="font-[family-name:var(--font-display)] text-[15px]">证伪命中</h2>
        <button
          type="button"
          className="rounded-md border border-[color:var(--color-line)] px-2.5 py-1 text-xs text-[color:var(--color-ink-dim)] disabled:opacity-60"
          disabled={loading}
          onClick={load}
        >
          刷新
        </button>
      </div>

      {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}

      <div className="space-y-2">
        <h3 className="text-sm font-medium text-[color:var(--color-ink)]">实时求值</h3>
        {realtime.length === 0 ? (
          <p className="text-sm text-[color:var(--color-ink-faint)]">
            暂无启用中的证伪条件——在上方投资策略面板添加并保存。
          </p>
        ) : (
          <ul className="space-y-2">
            {realtime.map((h, i) => (
              <HitRow key={h.falsifierId ?? i} hit={h} />
            ))}
          </ul>
        )}
      </div>

      <div className="space-y-2 border-t border-[color:var(--color-line-soft)] pt-4">
        <h3 className="text-sm font-medium text-[color:var(--color-ink)]">历史命中留痕</h3>
        {history.length === 0 ? (
          <p className="text-sm text-[color:var(--color-ink-faint)]">暂无历史命中（日终扫描落档）。</p>
        ) : (
          <ul className="space-y-2">
            {history.map((h) => (
              <HitRow key={h.id ?? h.falsifierId} hit={h} />
            ))}
          </ul>
        )}
      </div>

      <div className="flex flex-wrap items-center gap-2 border-t border-[color:var(--color-line-soft)] pt-4">
        <button type="button" className="rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)] text-[color:var(--color-ink-faint)] cursor-not-allowed" disabled>
          发起证伪评审
        </button>
        <span className="text-xs text-[color:var(--color-ink-faint)]">证伪评审将在复盘阶段开放（P4）。</span>
      </div>
    </section>
  );
}
