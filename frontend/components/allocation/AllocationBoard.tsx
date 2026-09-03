"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { fetchTemplates, fetchPlans, fetchDeviation } from "@/lib/allocationApi";
import type { TemplateView, PlanView, DeviationView } from "@/lib/types";
import DeviationChart from "./DeviationChart";
import PlanEditor from "./PlanEditor";
import PlanList from "./PlanList";

export default function AllocationBoard() {
  const [templates, setTemplates] = useState<TemplateView[]>([]);
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [deviation, setDeviation] = useState<DeviationView | null>(null);
  const [editing, setEditing] = useState<PlanView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    Promise.all([fetchTemplates(), fetchPlans(), fetchDeviation()])
      .then(([t, p, d]) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的 reload，丢弃过期响应
        setTemplates(t); setPlans(p); setDeviation(d);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载失败");
      });
  }, []);

  useEffect(() => { reload(); }, [reload]);

  if (error) return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">资产配置</h1>
      </div>
      <DeviationChart deviation={deviation} />
      <PlanEditor key={editing?.id ?? "new"} templates={templates} editing={editing} onSaved={() => { setEditing(null); reload(); }} />
      <PlanList plans={plans} onChanged={reload} onEdit={setEditing} />
    </div>
  );
}
