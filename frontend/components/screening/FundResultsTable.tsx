"use client";

import type { FundScreeningResult } from "@/lib/types";
import { TRACKING_ERROR_NOTE } from "./FundScreeningForm";

const COLUMNS: { key: keyof FundScreeningResult; label: string; sortKey: string; align: "left" | "right"; title?: string }[] = [
  { key: "fundCode", label: "代码", sortKey: "", align: "left" },
  { key: "fundName", label: "名称", sortKey: "", align: "left" },
  { key: "feeRate", label: "费率(%)", sortKey: "fee_rate", align: "right" },
  { key: "scale", label: "规模(亿元)", sortKey: "scale", align: "right" },
  { key: "trackingIndexName", label: "跟踪指数", sortKey: "", align: "left" },
  { key: "category", label: "类别", sortKey: "", align: "left" },
  // TE 为收盘价口径小数，表头 title 提示与官方净值口径不可直接对比
  { key: "trackingError1y", label: "跟踪误差(%)", sortKey: "tracking_error_1y", align: "right", title: TRACKING_ERROR_NOTE },
];

export default function FundResultsTable({ results, sortBy, sortDirection, onSort, watchlistCodes, onToggleWatchlist }: {
  results: FundScreeningResult[];
  sortBy: string;
  sortDirection: "ASC" | "DESC";
  onSort: (sortKey: string) => void;
  watchlistCodes?: Set<string>;
  onToggleWatchlist?: (fundCode: string) => void;
}) {
  // TE 后端为小数（0.0318 = 3.18%），展示 ×100 两位小数；null=未知「—」
  const fmtTe = (v: number | null) => (v == null ? "—" : (v * 100).toFixed(2));
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 overflow-x-auto">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">筛选结果（{results.length}）</div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            <th className="w-8 py-1" aria-label="自选" />
            {COLUMNS.map((c) => (
              <th key={c.key} title={c.title}
                  className={`py-1 ${c.align === "right" ? "text-right" : "text-left"} ${c.sortKey ? "cursor-pointer" : ""}`}
                  onClick={() => c.sortKey && onSort(c.sortKey)}>
                {c.label}{sortBy === c.sortKey ? (sortDirection === "ASC" ? " ↑" : " ↓") : ""}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="tabular">
          {results.map((r) => (
            <tr key={r.fundCode} className="border-t border-[color:var(--color-line-soft)]">
              <td className="py-2 text-center">
                {onToggleWatchlist && (
                  <button
                    type="button"
                    className="text-base leading-none"
                    aria-label={watchlistCodes?.has(r.fundCode) ? `移除自选 ${r.fundCode}` : `加自选 ${r.fundCode}`}
                    onClick={() => onToggleWatchlist(r.fundCode)}
                  >
                    {watchlistCodes?.has(r.fundCode) ? "★" : "☆"}
                  </button>
                )}
              </td>
              <td className="text-left py-2">{r.fundCode}</td>
              <td className="text-left py-2">{r.fundName}</td>
              <td className="text-right">{r.feeRate ?? "—"}</td>
              <td className="text-right">{r.scale ?? "—"}</td>
              <td className="text-left">{r.trackingIndexName ?? "—"}</td>
              <td className="text-left">{r.category ?? "—"}</td>
              <td className="text-right">{fmtTe(r.trackingError1y)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
