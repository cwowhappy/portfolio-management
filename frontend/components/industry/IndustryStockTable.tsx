"use client";

import { useState } from "react";
import type { IndustryStock, Prosperity } from "@/lib/types";

const PROSPERITY_LABEL: Record<Prosperity, string> = { UP: "↑", FLAT: "→", DOWN: "↓" };
const PAGE_SIZE = 50;

const fmtYi = (v: number | null) => (v == null ? "—" : (v / 1e8).toFixed(1));

export default function IndustryStockTable({ stocks, sortBy, sortDirection, onSort }: {
  stocks: IndustryStock[];
  sortBy: string;
  sortDirection: "ASC" | "DESC";
  onSort: (key: string) => void;
}) {
  const [page, setPage] = useState(0);
  // 排序变更回第 1 页：渲染期条件性调整状态（React 官方「You Might Not Need an Effect」范式），
  // 避免 effect 内同步 setState 触发级联渲染（react-hooks/set-state-in-effect）。
  const [prevSort, setPrevSort] = useState(`${sortBy}:${sortDirection}`);
  if (prevSort !== `${sortBy}:${sortDirection}`) {
    setPrevSort(`${sortBy}:${sortDirection}`);
    setPage(0);
  }

  const pages = Math.max(1, Math.ceil(stocks.length / PAGE_SIZE));
  const current = stocks.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE);
  const rankOffset = page * PAGE_SIZE;

  const cols = [
    { key: "", label: "排名" },
    { key: "", label: "代码 / 名称" },
    { key: "total_mv", label: "总市值(亿)" },
    { key: "revenue", label: "营收(亿)" },
    { key: "roe", label: "ROE" },
    { key: "", label: "PE" },
    { key: "", label: "PB" },
    { key: "", label: "股息率" },
    { key: "", label: "景气" },
  ];
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="industry-stocks-table">
      <div className="flex items-center justify-between mb-3">
        <div className="font-[family-name:var(--font-display)] text-[15px]">成员排名（{stocks.length}）</div>
        {pages > 1 && (
          <div className="flex items-center gap-2 text-sm">
            <button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>上一页</button>
            <span>第 {page + 1}/{pages} 页</span>
            <button disabled={page === pages - 1} onClick={() => setPage((p) => p + 1)}>下一页</button>
          </div>
        )}
      </div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            {cols.map((c, idx) => (
              <th key={idx} className={`py-1 ${idx > 1 ? "text-right" : "text-left"}`}
                  onClick={c.key ? () => onSort(c.key) : undefined}
                  style={c.key ? { cursor: "pointer" } : undefined}>
                {c.label}{c.key && sortBy === c.key ? (sortDirection === "ASC" ? " ↑" : " ↓") : ""}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="tabular">
          {current.map((s, i) => (
            <tr key={s.stockCode} className="border-t border-[color:var(--color-line-soft)]">
              <td className="text-left py-1.5">{rankOffset + i + 1}</td>
              <td className="text-left">
                {s.stockName} <span className="text-[color:var(--color-ink-dim)]">{s.stockCode}</span>
              </td>
              <td className="text-right">{fmtYi(s.totalMv)}</td>
              <td className="text-right">
                {fmtYi(s.revenue)}
                {s.revenueReportDate && (
                  <span className="ml-1 text-[11px] text-[color:var(--color-ink-dim)]">{s.revenueReportDate}</span>
                )}
              </td>
              <td className="text-right">{s.roe ?? "—"}</td>
              <td className="text-right">{s.peTtm ?? "—"}</td>
              <td className="text-right">{s.pb ?? "—"}</td>
              <td className="text-right">{s.dividendYield ?? "—"}</td>
              <td className="text-right">{s.prosperity ? PROSPERITY_LABEL[s.prosperity] : "—"}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
