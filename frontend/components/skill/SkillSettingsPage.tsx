"use client";

import { useEffect, useState } from "react";
import { fetchSkills, saveSkillConfig, SkillItem } from "@/lib/skillApi";

export default function SkillSettingsPage() {
  const [skills, setSkills] = useState<SkillItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  /** in-flight 防连点（全局互斥）：保存在途时忽略所有开关的后续触发 */
  const [pending, setPending] = useState(false);

  useEffect(() => {
    fetchSkills().then(setSkills).catch((e) => setError(e instanceof Error ? e.message : "数据加载失败"));
  }, []);

  const toggle = async (code: string) => {
    if (pending) return; // 防连点：上一次保存仍在途，本次触发直接忽略
    setPending(true);
    const next = skills.map((s) => s.skillCode === code ? { ...s, enabled: !s.enabled } : s);
    setSkills(next);
    try {
      const saved = await saveSkillConfig(next.filter((s) => s.enabled).map((s) => s.skillCode));
      setSkills(saved);
    } catch (e) {
      setError(e instanceof Error ? e.message : "操作失败");
    } finally {
      setPending(false);
    }
  };

  const groups = skills.reduce<Record<string, SkillItem[]>>((acc, s) => {
    const key = s.category ?? "other";
    (acc[key] ??= []).push(s);
    return acc;
  }, {});

  return (
    <div className="p-6 max-w-3xl mx-auto space-y-4">
      <h1 className="text-xl font-semibold">Skill 设置</h1>
      {error && <div className="text-[color:var(--color-down)] text-sm">{error}</div>}
      {Object.entries(groups).map(([category, items]) => (
        <div key={category} className="space-y-2">
          <h2 className="text-sm font-medium text-[color:var(--color-ink-dim)]">{category}</h2>
          {items.map((s) => (
            <div key={s.skillCode} className="border rounded-lg p-4 flex items-start justify-between gap-4">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{s.skillCode}</span>
                  {s.dependsOnProvider && (
                    <span className="text-xs bg-[color:var(--color-panel-2)] rounded px-2 py-0.5">依赖 {s.dependsOnProvider}</span>
                  )}
                </div>
                <p className="text-sm text-[color:var(--color-ink-faint)]">{s.description}</p>
              </div>
              <label className="flex items-center gap-2 text-sm shrink-0">
                <input type="checkbox" checked={s.enabled} disabled={pending} onChange={() => toggle(s.skillCode)} />
                <span>{s.enabled ? "已启用" : "已停用"}</span>
              </label>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
