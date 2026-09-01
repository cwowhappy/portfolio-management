"use client";

import type { ScreeningStock } from "@/lib/types";

const COLUMNS: { key: keyof ScreeningStock; label: string; sortKey: string }[] = [
  { key: "stockName", label: "名称", sortKey: "" },
  { key: "peTtm", label: "PE-TTM", sortKey: "pe_ttm" },
  { key: "pb", label: "PB", sortKey: "pb" },
  { key: "dividendYield", label: "股息率", sortKey: "dividend_yield" },
  { key: "roe", label: "ROE", sortKey: "roe" },
  { key: "roa", label: "ROA", sortKey: "roa" },
  { key: "grossMargin", label: "毛利率", sortKey: "gross_margin" },
  { key: "debtToAssets", label: "资产负债率", sortKey: "debt_to_assets" },
  { key: "revenueYoy", label: "营收增速", sortKey: "revenue_yoy" },
  { key: "netprofitYoy", label: "净利增速", sortKey: "netprofit_yoy" },
  { key: "totalMv", label: "总市值(亿)", sortKey: "total_mv" },
  { key: "turnoverRate", label: "换手率", sortKey: "turnover_rate" },
];

export default function ScreeningResultsTable({ results, sortBy, sortDirection, onSort }: {
  results: ScreeningStock[];
  sortBy: string;
  sortDirection: "ASC" | "DESC";
  onSort: (sortKey: string) => void;
}) {
  const fmtMv = (v: number | null) => (v == null ? "—" : (v / 1e8).toFixed(1));
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 overflow-x-auto">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">筛选结果（{results.length}）</div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            {COLUMNS.map((c) => (
              <th key={c.key} className={`text-right py-1 ${c.sortKey ? "cursor-pointer" : ""}`}
                  onClick={() => c.sortKey && onSort(c.sortKey)}>
                {c.label}{sortBy === c.sortKey ? (sortDirection === "ASC" ? " ↑" : " ↓") : ""}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="tabular">
          {results.map((r) => (
            <tr key={r.stockCode} className="border-t border-[color:var(--color-line-soft)]">
              <td className="text-left py-2">{r.stockName}<span className="ml-1 text-[color:var(--color-ink-faint)]">{r.stockCode}</span></td>
              <td className="text-right">{r.peTtm ?? "—"}</td>
              <td className="text-right">{r.pb ?? "—"}</td>
              <td className="text-right">{r.dividendYield ?? "—"}</td>
              <td className="text-right">{r.roe ?? "—"}</td>
              <td className="text-right">{r.roa ?? "—"}</td>
              <td className="text-right">{r.grossMargin ?? "—"}</td>
              <td className="text-right">{r.debtToAssets ?? "—"}</td>
              <td className="text-right">{r.revenueYoy ?? "—"}</td>
              <td className="text-right">{r.netprofitYoy ?? "—"}</td>
              <td className="text-right">{fmtMv(r.totalMv)}</td>
              <td className="text-right">{r.turnoverRate ?? "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
