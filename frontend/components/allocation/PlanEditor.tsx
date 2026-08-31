"use client";

import { useState } from "react";
import { ASSET_CLASSES, ASSET_CLASS_LABELS, createPlan, updatePlan } from "@/lib/allocationApi";
import type { AssetClass, PlanSource, PlanView, TemplateView } from "@/lib/types";

export default function PlanEditor({ templates, editing, onSaved }: { templates: TemplateView[]; editing: PlanView | null; onSaved: () => void }) {
  const [name, setName] = useState(editing?.name ?? "");
  const [source, setSource] = useState<PlanSource>(editing?.source ?? "CUSTOM");
  const [weights, setWeights] = useState<Record<AssetClass, number>>(() => {
    const init: Record<AssetClass, number> = { STOCK: 0, BOND: 0, GOLD: 0, CASH: 0, REITS: 0 };
    editing?.weights.forEach((w) => { init[w.assetClass] = w.weight; });
    return init;
  });
  const [error, setError] = useState<string | null>(null);

  const sum = ASSET_CLASSES.reduce((s, ac) => s + (weights[ac] ?? 0), 0);

  const applyTemplate = (t: TemplateView) => {
    const next: Record<AssetClass, number> = { STOCK: 0, BOND: 0, GOLD: 0, CASH: 0, REITS: 0 };
    t.weights.forEach((w) => { next[w.assetClass] = w.weight; });
    setWeights(next);
    setSource("TEMPLATE");
    if (!name) setName(t.name);
  };

  const setWeight = (ac: AssetClass, v: number) => {
    setWeights((prev) => ({ ...prev, [ac]: v }));
    setSource("CUSTOM");
  };

  const save = async () => {
    setError(null);
    const list = ASSET_CLASSES.filter((ac) => (weights[ac] ?? 0) > 0)
      .map((ac) => ({ assetClass: ac, weight: weights[ac]! }));
    if (list.length === 0) { setError("请至少设置一类资产权重"); return; }
    if (sum !== 100) { setError(`权重之和需为 100%（当前 ${sum}%）`); return; }
    try {
      if (editing) {
        await updatePlan(editing.id, { name: name.trim() || editing.name, weights: list });
      } else {
        await createPlan({ name: name.trim() || "未命名方案", source, weights: list });
      }
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">{editing ? "编辑方案" : "新建方案"}</div>
      <div className="flex flex-wrap gap-2 mb-3">
        {templates.map((t) => (
          <button key={t.id} className="rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)]" onClick={() => applyTemplate(t)}>
            {t.name}
          </button>
        ))}
      </div>
      <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm mb-3 w-64" placeholder="方案名" value={name} onChange={(e) => setName(e.target.value)} />
      <div className="grid grid-cols-2 md:grid-cols-5 gap-3 mb-3">
        {ASSET_CLASSES.map((ac) => (
          <label key={ac} className="text-sm">
            {ASSET_CLASS_LABELS[ac]}
            <input type="number" min={0} max={100} className="mt-1 w-full rounded-md border border-[color:var(--color-line)] px-2 py-1" value={weights[ac] ?? 0} onChange={(e) => setWeight(ac, Number(e.target.value))} />
          </label>
        ))}
      </div>
      <div className="text-sm text-[color:var(--color-ink-faint)] mb-3">权重合计：{sum}%</div>
      {error && <div className="text-sm text-[color:var(--color-down)] mb-3">{error}</div>}
      <button className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]" onClick={save}>{editing ? "保存修改" : "保存方案"}</button>
    </div>
  );
}
