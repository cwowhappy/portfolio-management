"use client";

import type { IndustryValuation } from "@/lib/types";

export function rank(values: (number | null)[], value: number | null): number | null {
  if (value == null) return null;
  const nonNull = values.filter((v): v is number => v != null);
  if (nonNull.length === 0) return null;
  const below = nonNull.filter((v) => v < value).length;
  const equal = nonNull.filter((v) => v === value).length;
  // 中点百分位排名：below + equal/2，中位数恰好落 0.5，0..1 相对分位。
  return (below + equal / 2) / nonNull.length;
}

function color(rankValue: number | null, reverse: boolean): string {
  if (rankValue == null) return "var(--color-line-soft)";
  const p = reverse ? 1 - rankValue : rankValue; // 0=低估/好, 1=高估/差
  const hue = p < 0.5 ? 120 * (1 - p / 0.5) + 45 * (p / 0.5) : 45 * (1 - (p - 0.5) / 0.5);
  return `hsl(${Math.round(hue)} 70% 45%)`;
}

export default function ValuationHeatmap({ industries }: { industries: IndustryValuation[] }) {
  const cols = [
    { key: "pe", label: "PE", values: industries.map((i) => i.pe), reverse: false },
    { key: "pb", label: "PB", values: industries.map((i) => i.pb), reverse: false },
    { key: "roe", label: "ROE", values: industries.map((i) => i.roe), reverse: true },
  ] as const;
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">估值热力图</div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            <th className="text-left py-1">行业</th>
            {cols.map((c) => <th key={c.key} className="text-center py-1">{c.label}</th>)}
          </tr>
        </thead>
        <tbody>
          {industries.map((i) => (
            <tr key={i.industryCode} className="border-t border-[color:var(--color-line-soft)]">
              <td className="text-left py-1">{i.industryName}</td>
              {cols.map((c) => {
                const r = rank(c.values, i[c.key]);
                return (
                  <td key={c.key} className="text-center py-1">
                    <span className="inline-block w-14 rounded px-1 tabular" style={{ background: color(r, c.reverse) }}>
                      {i[c.key] ?? "—"}
                    </span>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
      <div className="mt-2 text-xs text-[color:var(--color-ink-faint)]">颜色为该指标在行业间的相对分位：绿=低估/高 ROE，红=高估/低 ROE。</div>
    </div>
  );
}
