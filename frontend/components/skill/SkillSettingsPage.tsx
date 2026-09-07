"use client";

import { useEffect, useState } from "react";
import { fetchSkills, saveSkillConfig, SkillItem } from "@/lib/skillApi";

export default function SkillSettingsPage() {
  const [skills, setSkills] = useState<SkillItem[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchSkills().then(setSkills).catch((e) => setError(e.message));
  }, []);

  const toggle = async (code: string) => {
    const next = skills.map((s) => s.skillCode === code ? { ...s, enabled: !s.enabled } : s);
    setSkills(next);
    try {
      const saved = await saveSkillConfig(next.filter((s) => s.enabled).map((s) => s.skillCode));
      setSkills(saved);
    } catch (e) { setError((e as Error).message); }
  };

  const groups = skills.reduce<Record<string, SkillItem[]>>((acc, s) => {
    const key = s.category ?? "other";
    (acc[key] ??= []).push(s);
    return acc;
  }, {});

  return (
    <div className="p-6 max-w-3xl mx-auto space-y-4">
      <h1 className="text-xl font-semibold">Skill 设置</h1>
      {error && <div className="text-red-600 text-sm">{error}</div>}
      {Object.entries(groups).map(([category, items]) => (
        <div key={category} className="space-y-2">
          <h2 className="text-sm font-medium text-gray-500">{category}</h2>
          {items.map((s) => (
            <div key={s.skillCode} className="border rounded-lg p-4 flex items-start justify-between gap-4">
              <div className="space-y-1">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{s.skillCode}</span>
                  {s.dependsOnProvider && (
                    <span className="text-xs bg-gray-100 rounded px-2 py-0.5">依赖 {s.dependsOnProvider}</span>
                  )}
                </div>
                <p className="text-sm text-gray-600">{s.description}</p>
              </div>
              <label className="flex items-center gap-2 text-sm shrink-0">
                <input type="checkbox" checked={s.enabled} onChange={() => toggle(s.skillCode)} />
                <span>{s.enabled ? "已启用" : "已停用"}</span>
              </label>
            </div>
          ))}
        </div>
      ))}
    </div>
  );
}
