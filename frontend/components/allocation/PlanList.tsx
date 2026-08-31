"use client";

import { activatePlan, deletePlan } from "@/lib/allocationApi";
import type { PlanView } from "@/lib/types";

export default function PlanList({ plans, onChanged, onEdit }: { plans: PlanView[]; onChanged: () => void; onEdit: (p: PlanView) => void }) {
  const activate = async (id: number) => { await activatePlan(id); onChanged(); };
  const remove = async (id: number) => { if (confirm("删除该方案？")) { await deletePlan(id); onChanged(); } };

  if (plans.length === 0) {
    return <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 text-sm text-[color:var(--color-ink-faint)]">暂无方案</div>;
  }
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="plan-list">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">方案列表</div>
      <ul className="space-y-2">
        {plans.map((p) => (
          <li key={p.id} className="flex items-center justify-between text-sm" data-testid="plan-item">
            <span>
              {p.name}
              {p.active && <span className="ml-2 text-xs text-[color:var(--color-up)]">生效</span>}
            </span>
            <span className="flex gap-2">
              <button className="rounded-md px-2 py-1 text-xs border border-[color:var(--color-line)]" onClick={() => onEdit(p)}>编辑</button>
              {!p.active && <button className="rounded-md px-2 py-1 text-xs border border-[color:var(--color-line)]" onClick={() => activate(p.id)}>设为生效</button>}
              <button className="rounded-md px-2 py-1 text-xs border border-[color:var(--color-line)]" onClick={() => remove(p.id)}>删除</button>
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
