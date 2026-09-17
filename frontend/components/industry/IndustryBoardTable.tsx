"use client";

import { useState } from "react";
import type { IndustryBoardItem, Prosperity } from "@/lib/types";

type SortKey = "pe" | "pb" | "roe" | "dividendYield" | "pePercentile" | "pbPercentile";

const PROSPERITY_LABEL: Record<Prosperity, string> = { UP: "↑", FLAT: "→", DOWN: "↓" };

/** 景气/分位口径提示（回溯近似显式化，需求 §三.A）。 */
const PERCENTILE_NOTE = "当前值在近 5 年序列经验分布中的百分位（高=贵）；历史按当前申万 2021 分类成分回溯重算";

export default function IndustryBoardTable({ items, onSelect }: {
  items: IndustryBoardItem[];
  onSelect?: (industryCode: string) => void;
}) {
  const [sort, setSort] = useState<SortKey>("pe");
  const sorted = [...items].sort((a, b) => (b[sort] ?? 0) - (a[sort] ?? 0));
  const cols: [SortKey, string][] = [
    ["pe", "PE"], ["pb", "PB"], ["roe", "ROE"], ["dividendYield", "股息率"],
    ["pePercentile", "PE 5y分位"], ["pbPercentile", "PB 5y分位"],
  ];
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="industry-board-table">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">行业估值对比</div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            <th className="text-left py-1">行业</th>
            {cols.map(([k, label]) => (
              <th key={k} className="text-right py-1 cursor-pointer"
                  onClick={() => setSort(k)}
                  title={k.endsWith("Percentile") ? PERCENTILE_NOTE : undefined}>
                {label}{sort === k ? " ↓" : ""}
              </th>
            ))}
            <th className="text-right py-1">景气</th>
          </tr>
        </thead>
        <tbody className="tabular">
          {sorted.map((i) => (
            <tr key={i.industryCode} className="border-t border-[color:var(--color-line-soft)]">
              <td className="text-left py-2">
                {onSelect ? (
                  <button className="text-[color:var(--color-up)] hover:underline"
                          onClick={() => onSelect(i.industryCode)}>{i.industryName}</button>
                ) : i.industryName}
              </td>
              <td className="text-right">{i.pe ?? "—"}</td>
              <td className="text-right">{i.pb ?? "—"}</td>
              <td className="text-right">{i.roe ?? "—"}</td>
              <td className="text-right">{i.dividendYield ?? "—"}</td>
              <td className="text-right">{fmtPct(i.pePercentile)}</td>
              <td className="text-right">{fmtPct(i.pbPercentile)}</td>
              <td className="text-right">
                {i.prosperity ? (
                  <span title={prosperityTitle(i)}>
                    {PROSPERITY_LABEL[i.prosperity]}
                  </span>
                ) : "—"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function fmtPct(v: number | null): string {
  return v == null ? "—" : `${v.toFixed(1)}%`;
}

function prosperityTitle(i: IndustryBoardItem): string {
  const p = i.prosperityInputs;
  if (!p) return "";
  return `ROEΔ中位数 ${p.roeDeltaMedian ?? "—"}pp · 营收增速中位数 ${p.revenueYoyMedian ?? "—"}% · 样本 ${p.sampleSize}（近4季均值 vs 前4季；上行=Δ≥+1且增速≥+10）`;
}
