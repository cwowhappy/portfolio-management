"use client";
// 投研草稿只读卡（invest-sop P1，草稿围栏双通道的前端半边）。卡片壳照 InterruptApprovalCard，
// safeParse + 判别分发 + 失败降级照 ChartCard：raw 为渲染器从工具结果 ```research-draft 围栏
// 提取的 JSON 文本；解析失败/版本不识别 → 降级折叠卡「草稿格式不兼容」，不白屏不抛错。
// 「保存到项目」P1 为 no-op 提示（研究项目落库 API 在 P2 接通，D9/D20）。
import { useMemo, useState, type ReactNode } from "react";
import {
  ResearchDraftSchema,
  type AnalysisDraft,
  type EntryPlanDraft,
  type ReviewDraft,
  type StrategyDraft,
} from "@/lib/research-draft";

/** stage → 中文标题（与 InvestTools 工具描述四阶段文案对齐） */
const STAGE_TITLES = {
  NEW_ANALYSIS: "新分析",
  STRATEGY: "策略",
  POSITION: "建仓计划",
  REVIEW: "复盘",
} as const;

/** 表单行：label 固定宽 + 值；值缺省的行整行不渲染（草稿本就允许部分字段，后端 NON_NULL 剥离） */
function Row({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mt-1.5 flex gap-2 text-[12px] leading-relaxed">
      <span className="w-16 shrink-0 text-[color:var(--color-ink-faint)]">{label}</span>
      <span className="min-w-0 flex-1 whitespace-pre-wrap break-words text-[color:var(--color-ink-dim)]">
        {children}
      </span>
    </div>
  );
}

/** 行集渲染：值为空的行跳过；全空出兜底文案（draftJson 容忍全缺省） */
function DraftRows({ rows }: { rows: Array<[string, ReactNode | null]> }) {
  const present = rows.filter(([, v]) => v != null && v !== "");
  if (present.length === 0) {
    return <p className="mt-1.5 text-[12px] text-[color:var(--color-ink-faint)]">草稿暂无内容</p>;
  }
  return (
    <div>
      {present.map(([label, value]) => (
        <Row key={label} label={label}>
          {value}
        </Row>
      ))}
    </div>
  );
}

/** 区间拼接：两侧任一存在即 a~b，单侧仅该侧（与后端 joinTilde 同语义） */
function rangeText(low?: string | number, high?: string | number): string | null {
  if (low == null && high == null) return null;
  if (low == null) return String(high);
  return high == null ? String(low) : `${low}~${high}`;
}

function AnalysisRows({ draft }: { draft: AnalysisDraft }) {
  const target = [draft.symbol, draft.companyName].filter(Boolean).join(" ");
  return (
    <DraftRows
      rows={[
        ["标的", target || null],
        ["行业", draft.industry ?? null],
        ["已完成清单", draft.checklistDone?.length ? draft.checklistDone.join("、") : null],
        ["摘要", draft.summary ?? null],
      ]}
    />
  );
}

function StrategyRows({ draft }: { draft: StrategyDraft }) {
  return (
    <DraftRows
      rows={[
        ["投资逻辑", draft.thesis ?? null],
        ["估值区间", rangeText(draft.valuationLow, draft.valuationHigh)],
        ["仓位计划", draft.positionPlan ?? null],
        ["买入条件", draft.buyConditions ?? null],
        [
          "证伪条件",
          draft.riskItems?.length ? (
            <>
              {draft.riskItems.map((it, i) => (
                <div key={i}>
                  {[it.kind, it.predicate, it.threshold == null ? null : `阈值 ${it.threshold}`, it.note]
                    .filter(Boolean)
                    .join(" · ")}
                </div>
              ))}
            </>
          ) : null,
        ],
      ]}
    />
  );
}

function EntryPlanRows({ draft }: { draft: EntryPlanDraft }) {
  return (
    <DraftRows
      rows={[
        [
          "建仓批次",
          draft.batches?.length ? (
            <>
              {draft.batches.map((b, i) => (
                <div key={i}>
                  {[
                    `第${i + 1}批`,
                    rangeText(b.priceLow, b.priceHigh),
                    `${b.quantity} 股`,
                    b.ratio == null ? null : `占比 ${b.ratio}`,
                  ]
                    .filter(Boolean)
                    .join(" · ")}
                </div>
              ))}
            </>
          ) : null,
        ],
        ["胜率", draft.winRate ?? null],
        ["盈亏比", draft.payoffRatio ?? null],
        ["备注", draft.note ?? null],
      ]}
    />
  );
}

function ReviewRows({ draft }: { draft: ReviewDraft }) {
  return (
    <DraftRows
      rows={[
        ["档期", draft.tier ?? null],
        ["复盘区间", rangeText(draft.periodStart, draft.periodEnd)],
        ["复盘叙述", draft.narrative ?? null],
      ]}
    />
  );
}

export interface DraftCardProps {
  /** 渲染器从工具结果围栏提取的 JSON 文本（无围栏时为原始文本，走降级卡） */
  raw: string;
}

/** 草稿只读卡：safeParse 成功 → 表单化只读展示 + 「保存到项目」（P1 no-op 提示）；失败 → 降级折叠卡。 */
export default function DraftCard({ raw }: DraftCardProps) {
  const draft = useMemo(() => {
    let json: unknown;
    try {
      json = JSON.parse(raw);
    } catch {
      return null;
    }
    const check = ResearchDraftSchema.safeParse(json);
    return check.success ? check.data : null;
  }, [raw]);
  const [toast, setToast] = useState(false);

  if (draft == null) {
    return (
      <details className="tool-card my-2 w-full max-w-[560px] px-3 py-2 text-xs">
        <summary className="cursor-pointer select-none text-[color:var(--color-ink-dim)]">
          草稿格式不兼容（原始内容折叠）
        </summary>
        <pre className="mt-2 max-h-[320px] overflow-auto whitespace-pre-wrap break-all text-[color:var(--color-ink-faint)]">
          {raw.slice(0, 2000)}
        </pre>
      </details>
    );
  }

  return (
    <div className="tool-card my-2 w-full max-w-[560px] px-3.5 py-2.5">
      <p className="text-[13px] font-medium text-[color:var(--color-ink)]">
        投研草稿 · {STAGE_TITLES[draft.stage]}
      </p>
      {draft.stage === "NEW_ANALYSIS" && <AnalysisRows draft={draft} />}
      {draft.stage === "STRATEGY" && <StrategyRows draft={draft} />}
      {draft.stage === "POSITION" && <EntryPlanRows draft={draft} />}
      {draft.stage === "REVIEW" && <ReviewRows draft={draft} />}
      <div className="mt-2.5 flex items-center gap-2.5">
        <button
          type="button"
          onClick={() => setToast(true)}
          className="rounded-md border border-[color:var(--color-up)]/50 px-3 py-1 text-[12px] text-[color:var(--color-up)] hover:bg-[color:var(--color-up)]/10"
        >
          保存到项目
        </button>
        {toast && (
          <span className="text-[12px] text-[color:var(--color-ink-faint)]">研究项目功能即将上线</span>
        )}
      </div>
    </div>
  );
}
