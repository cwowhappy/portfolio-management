"use client";

import { useState } from "react";
import type { IndustryValuation } from "@/lib/types";

export default function IndustryTable({ industries, onSelect }: {
  industries: IndustryValuation[];
  onSelect?: (industryCode: string) => void;
}) {
  // 排序键使用 IndustryValuation 真实字段名（dividendYield），表头文案仍显示「股息率」
  const [sort, setSort] = useState<"pe" | "pb" | "roe" | "dividendYield">("pe");
  const sorted = [...industries].sort((a, b) => (a[sort] ?? 0) - (b[sort] ?? 0));
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">行业估值对比</div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            <th className="text-left py-1">行业</th>
            {([["pe", "PE"], ["pb", "PB"], ["roe", "ROE"], ["dividendYield", "股息率"]] as const).map(([k, label]) => (
              <th key={k} className="text-right py-1 cursor-pointer" onClick={() => setSort(k)}>{label}{sort === k ? " ↓" : ""}</th>
            ))}
          </tr>
        </thead>
        <tbody className="tabular">
          {sorted.map((i) => (
            <tr key={i.industryCode} className="border-t border-[color:var(--color-line-soft)]">
              <td className="text-left py-2">
                {onSelect ? (
                  <button className="text-[color:var(--color-up)] hover:underline" onClick={() => onSelect(i.industryCode)}>
                    {i.industryName}
                  </button>
                ) : i.industryName}
              </td>
              <td className="text-right">{i.pe ?? "—"}</td>
              <td className="text-right">{i.pb ?? "—"}</td>
              <td className="text-right">{i.roe ?? "—"}</td>
              <td className="text-right">{i.dividendYield ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
