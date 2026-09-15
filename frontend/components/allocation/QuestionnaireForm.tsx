"use client";

import { useEffect, useState } from "react";
import { fetchQuestionnaire } from "@/lib/allocationApi";
import type { QuestionnaireView } from "@/lib/types";

export default function QuestionnaireForm({ onSubmit, onCancel }: {
  onSubmit: (answers: { questionId: string; optionId: string }[]) => Promise<void>;
  onCancel: () => void;
}) {
  const [questionnaire, setQuestionnaire] = useState<QuestionnaireView | null>(null);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    fetchQuestionnaire().then(setQuestionnaire).catch((e) => {
      setError(e instanceof Error ? e.message : "题库加载失败");
    });
  }, []);

  if (error) return <div className="text-sm text-[color:var(--color-down)]">题库加载失败：{error}</div>;
  if (!questionnaire) return <div className="text-sm text-[color:var(--color-ink-dim)]">题库加载中…</div>;

  const complete = Object.keys(answers).length === questionnaire.questions.length;

  const submit = async () => {
    setSubmitting(true);
    setError(null);
    try {
      await onSubmit(Object.entries(answers).map(([questionId, optionId]) => ({ questionId, optionId })));
    } catch (e) {
      setError(e instanceof Error ? e.message : "提交失败");
      setSubmitting(false);
    }
  };

  return (
    <div data-testid="questionnaire-form" className="space-y-4">
      {questionnaire.questions.map((q) => (
        <fieldset key={q.id} className="rounded-xl border border-[color:var(--color-line)] p-4">
          <legend className="text-sm text-[color:var(--color-ink-faint)]">{q.dimension}</legend>
          <div className="mb-2 text-sm">{q.text}</div>
          <div className="flex flex-col gap-1">
            {q.options.map((o) => (
              <label key={o.id} className="text-sm cursor-pointer">
                <input
                  type="radio"
                  name={q.id}
                  className="mr-2"
                  checked={answers[q.id] === o.id}
                  onChange={() => setAnswers((prev) => ({ ...prev, [q.id]: o.id }))}
                />
                {o.text}
              </label>
            ))}
          </div>
        </fieldset>
      ))}
      {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}
      <div className="flex gap-2">
        <button
          className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)] disabled:opacity-40"
          disabled={!complete || submitting}
          onClick={submit}
        >
          提交问卷
        </button>
        <button className="rounded-md px-4 py-1.5 text-sm border border-[color:var(--color-line)]" onClick={onCancel}>
          取消
        </button>
      </div>
    </div>
  );
}
