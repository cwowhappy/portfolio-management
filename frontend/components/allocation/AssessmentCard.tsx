"use client";

import { useState } from "react";
import { ASSET_CLASS_LABELS, createPlan, submitAssessment } from "@/lib/allocationApi";
import type { AssessmentView } from "@/lib/types";
import QuestionnaireForm from "./QuestionnaireForm";

export default function AssessmentCard({ assessment, onChanged }: {
  assessment: AssessmentView | null;
  onChanged: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (answers: { questionId: string; optionId: string }[]) => {
    await submitAssessment(answers);
    setOpen(false);
    onChanged();
  };

  const createFromAssessment = async () => {
    if (!assessment) return;
    setCreating(true);
    setError(null);
    try {
      await createPlan({
        name: `测评推荐·${assessment.profileName}`,
        source: "ASSESSMENT",
        weights: assessment.weights,
      });
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建失败");
    } finally {
      setCreating(false);
    }
  };

  return (
    <div data-testid="assessment-card" className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
      <div className="flex items-center justify-between">
        <div className="font-[family-name:var(--font-display)] text-[15px]">风险测评</div>
        {assessment && (
          <button className="text-sm text-[color:var(--color-ink-faint)] underline" onClick={() => setOpen(true)}>
            重新测评
          </button>
        )}
      </div>

      {assessment ? (
        <div className="space-y-2">
          <div className="flex items-center gap-3">
            <span data-testid="assessment-profile" className="rounded-full px-3 py-1 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]">
              {assessment.profileName}
            </span>
            <span className="text-sm text-[color:var(--color-ink-dim)]">
              总分 {assessment.totalScore}/40 · {new Date(assessment.assessedAt).toLocaleString("zh-CN")}
            </span>
          </div>
          <div className="flex flex-wrap gap-2 text-sm">
            {assessment.weights.map((w) => (
              <span key={w.assetClass} className="rounded-md border border-[color:var(--color-line)] px-2 py-0.5">
                {ASSET_CLASS_LABELS[w.assetClass]} {w.weight}%
              </span>
            ))}
          </div>
          {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
          <button
            className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)] disabled:opacity-40"
            disabled={creating}
            onClick={createFromAssessment}
          >
            按推荐创建方案
          </button>
        </div>
      ) : (
        !open && (
          <button className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]" onClick={() => setOpen(true)}>
            开始测评
          </button>
        )
      )}

      {open && <QuestionnaireForm onSubmit={submit} onCancel={() => setOpen(false)} />}
    </div>
  );
}
