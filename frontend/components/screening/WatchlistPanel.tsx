"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { addToWatchlist, fetchWatchlist, removeFromWatchlist, searchStocks } from "@/lib/watchlistApi";
import type { StockSearchHit, WatchlistItemView } from "@/lib/types";

const fmtMv = (v: number | null) => (v == null ? "—" : (v / 1e8).toFixed(1));

/** 自选观察面板：搜索添加（代码/名称候选）+ 列表（实时现价 + 收盘快照口径）+ 移除。
 *  authenticated=false（匿名）时直接展示登录引导，不发起需登录的请求。 */
export default function WatchlistPanel({ authenticated = true }: { authenticated?: boolean }) {
  const [rows, setRows] = useState<WatchlistItemView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [query, setQuery] = useState("");
  const [candidates, setCandidates] = useState<StockSearchHit[]>([]);
  const [busy, setBusy] = useState(false);
  const debounceRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const reload = useCallback(() => {
    return fetchWatchlist()
      .then(setRows)
      .catch((e) => {
        setRows([]);
        setError(e instanceof Error ? e.message : "加载失败");
      });
  }, []);

  useEffect(() => { if (authenticated) void reload(); }, [authenticated, reload]);

  const onQueryChange = (q: string) => {
    setQuery(q);
    setCandidates([]);
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (!q.trim()) return;
    debounceRef.current = setTimeout(() => {
      searchStocks(q.trim())
        .then(setCandidates)
        .catch(() => setCandidates([]));
    }, 300);
  };

  const onPick = (code: string) => {
    setBusy(true);
    setCandidates([]);
    setQuery("");
    addToWatchlist(code)
      .then(() => reload())
      .catch((e) => setError(e instanceof Error ? e.message : "添加失败"))
      .finally(() => setBusy(false));
  };

  const onRemove = (code: string) => {
    removeFromWatchlist(code)
      .then(() => reload())
      .catch((e) => setError(e instanceof Error ? e.message : "移除失败"));
  };

  return (
    <div data-testid="watchlist-panel" className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
      <div className="text-[15px] font-[family-name:var(--font-display)]">自选观察</div>

      {!authenticated ? (
        <div className="text-sm text-[color:var(--color-ink-dim)]">登录后可查看自选（右上角登录，或从筛选结果点 ⭐ 加入）</div>
      ) : (
        <>
          <div className="relative">
            <input
              className="w-72 rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-3 py-1.5 text-sm"
              placeholder="搜索代码或名称添加"
              value={query}
              onChange={(e) => onQueryChange(e.target.value)}
              disabled={busy}
            />
            {candidates.length > 0 && (
              <ul className="absolute z-10 mt-1 w-96 max-h-64 overflow-auto rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-bg)] shadow-lg text-sm">
                {candidates.map((c) => (
                  <li key={c.stockCode}>
                    <button
                      type="button"
                      className="flex w-full items-center justify-between gap-3 px-3 py-1.5 text-left hover:bg-[color:var(--color-panel)]"
                      onClick={() => onPick(c.stockCode)}
                    >
                      <span>{c.stockCode} {c.stockName}</span>
                      <span className="text-xs text-[color:var(--color-ink-faint)]">
                        {c.industryName ?? ""} · PE {c.peTtm ?? "—"}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {error && <div className="text-xs text-[color:var(--color-down)]">{error}</div>}

          {rows == null ? (
            <div className="text-sm text-[color:var(--color-ink-dim)]">加载中…</div>
          ) : rows.length === 0 ? (
            <div className="text-sm text-[color:var(--color-ink-dim)]">暂无自选——从筛选结果点 ⭐ 或在上方搜索添加</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-[color:var(--color-ink-dim)]">
                  <tr>
                    <th className="text-left py-1">代码</th>
                    <th className="text-left py-1">名称</th>
                    <th className="text-left py-1">行业</th>
                    <th className="text-right py-1">现价</th>
                    <th className="text-right py-1">PE-TTM</th>
                    <th className="text-right py-1">PB</th>
                    <th className="text-right py-1">股息率%</th>
                    <th className="text-right py-1">总市值(亿)</th>
                    <th className="text-right py-1">添加时间</th>
                    <th className="py-1" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((r) => (
                    <tr key={r.stockCode} className="border-t border-[color:var(--color-line-soft)]">
                      <td className="py-1.5">{r.stockCode}</td>
                      <td>{r.stockName ?? "—"}</td>
                      <td className="text-[color:var(--color-ink-dim)]">{r.industryName ?? "—"}</td>
                      <td className="text-right" data-testid={`watchlist-price-${r.stockCode}`}>
                        {r.price == null ? "—" : r.price.toFixed(2)}
                      </td>
                      <td className="text-right">{r.peTtm ?? "—"}</td>
                      <td className="text-right">{r.pb ?? "—"}</td>
                      <td className="text-right">{r.dividendYield ?? "—"}</td>
                      <td className="text-right">{fmtMv(r.totalMv)}</td>
                      <td className="text-right text-[color:var(--color-ink-faint)]">
                        {new Date(r.addedAt).toLocaleDateString("zh-CN")}
                      </td>
                      <td className="text-right">
                        <button
                          type="button"
                          className="text-xs text-[color:var(--color-ink-faint)] hover:text-[color:var(--color-down)]"
                          aria-label={`移除 ${r.stockCode}`}
                          onClick={() => onRemove(r.stockCode)}
                        >
                          移除
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          <div className="text-xs text-[color:var(--color-ink-faint)]">现价为实时行情，其余为最新收盘快照（tushare）</div>
        </>
      )}
    </div>
  );
}
