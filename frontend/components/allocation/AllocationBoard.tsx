"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { fetchTemplates, fetchPlans, fetchDeviation, fetchAssessment, fetchRebalance, ackRebalance } from "@/lib/allocationApi";
import type { TemplateView, PlanView, DeviationView, AssessmentView, RebalanceView } from "@/lib/types";
import AssessmentCard from "./AssessmentCard";
import BacktestCard from "./BacktestCard";
import DeviationChart from "./DeviationChart";
import PlanEditor from "./PlanEditor";
import PlanList from "./PlanList";
import RebalanceCard from "./RebalanceCard";

/** ack/激活后再平衡状态变化，通知导航红点重拉（layout 的 AllocationAlertDot 监听）。 */
function notifyRebalanceRefresh() {
  window.dispatchEvent(new CustomEvent("rebalance-refresh"));
}

export default function AllocationBoard() {
  const [templates, setTemplates] = useState<TemplateView[]>([]);
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [deviation, setDeviation] = useState<DeviationView | null>(null);
  const [assessment, setAssessment] = useState<AssessmentView | null>(null);
  const [rebalance, setRebalance] = useState<RebalanceView | null>(null);
  const [editing, setEditing] = useState<PlanView | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [ackBusy, setAckBusy] = useState(false);
  const requestSeqRef = useRef(0);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    Promise.all([fetchTemplates(), fetchPlans(), fetchDeviation(), fetchAssessment(), fetchRebalance()])
      .then(([t, p, d, a, r]) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的 reload，丢弃过期响应
        setTemplates(t); setPlans(p); setDeviation(d); setAssessment(a ?? null); setRebalance(r);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载失败");
      });
  }, []);

  useEffect(() => { reload(); }, [reload]);

  const onAck = useCallback(() => {
    setAckBusy(true);
    ackRebalance()
      .then(() => { notifyRebalanceRefresh(); reload(); })
      .catch((e) => setError(e instanceof Error ? e.message : "操作失败"))
      .finally(() => setAckBusy(false));
  }, [reload]);

  const onPlanChanged = useCallback(() => {
    notifyRebalanceRefresh(); // 激活/编辑会重置锚点或改变目标，红点需刷新
    reload();
  }, [reload]);

  if (error) return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">资产配置</h1>
      </div>
      <AssessmentCard assessment={assessment} onChanged={reload} />
      <RebalanceCard view={rebalance} onAck={onAck} ackBusy={ackBusy} />
      <DeviationChart deviation={deviation} />
      {/* 回测卡独立自取 plans/templates，不接入 Board 既有 state/reload */}
      <BacktestCard />
      <PlanEditor key={editing?.id ?? "new"} templates={templates} editing={editing} onSaved={() => { setEditing(null); onPlanChanged(); }} />
      <PlanList plans={plans} onChanged={onPlanChanged} onEdit={setEditing} />
    </div>
  );
}
