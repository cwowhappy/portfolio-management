"use client";

import { useState } from "react";
import { createGroup } from "@/lib/portfolioApi";
import type { GroupView } from "@/lib/types";

const inputClass =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)] px-3 py-2 text-[14px] text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:border-[color:var(--color-up)] focus:outline-none";

export default function GroupManager({ groups, onChanged }: { groups: GroupView[]; onChanged: () => void }) {
  const [name, setName] = useState("");
  const [type, setType] = useState<"ACCOUNT" | "TAG">("ACCOUNT");
  const [busy, setBusy] = useState(false);

  async function onSubmit() {
    if (!name.trim()) return;
    setBusy(true);
    try {
      await createGroup({ name: name.trim(), type });
      setName("");
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">分组</div>
      <div className="flex gap-2 text-sm">
        <input
          className={`${inputClass} flex-1 min-w-0`}
          placeholder="分组名（如 华泰）"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <select className={inputClass} value={type} onChange={(e) => setType(e.target.value as "ACCOUNT" | "TAG")}>
          <option value="ACCOUNT">账户</option>
          <option value="TAG">标签</option>
        </select>
        <button
          className="rounded-md bg-[color:var(--color-up)] px-3 py-1.5 text-white disabled:opacity-50"
          onClick={onSubmit}
          disabled={busy || !name.trim()}
        >
          新建
        </button>
      </div>
      <ul className="mt-3 space-y-1 text-sm">
        {groups.map((g) => (
          <li key={g.id} className="flex justify-between">
            <span>
              {g.name}
              <span className="ml-2 text-xs text-[color:var(--color-ink-faint)]">{g.type === "ACCOUNT" ? "账户" : "标签"}</span>
            </span>
            {g.type === "ACCOUNT" && <span className="tabular">现金 {g.cashBalance.toFixed(2)}</span>}
          </li>
        ))}
      </ul>
    </div>
  );
}
