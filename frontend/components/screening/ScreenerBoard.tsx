"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import { buildExportHref, fetchScreenedStocks } from "@/lib/screeningApi";
import { addToWatchlist, fetchWatchlist } from "@/lib/watchlistApi";
import { fetchValuationIndustries } from "@/lib/valuationApi";
import { useAuth } from "@/lib/auth";
import type { IndustryValuation, ScreeningParams, ScreeningStock } from "@/lib/types";
import Disclaimer from "@/components/Disclaimer";
import ScreeningForm from "./ScreeningForm";
import ScreeningResultsTable from "./ScreeningResultsTable";
import WatchlistPanel from "./WatchlistPanel";

const INDEX_LABELS: Record<string, string> = { "000300": "沪深300", "000905": "中证500" };

export default function ScreenerBoard() {
  // 行业页跳转「/screener?industryCode=…」带入行业条件：挂载时读取一次并初始化到 params，
  // 避免行业条件丢失（否则提交会误报「请至少填写一个筛选条件」）。
  const searchParams = useSearchParams();
  const router = useRouter();
  const { user } = useAuth();
  const [tab, setTab] = useState<"screener" | "watchlist">("screener");
  const [params, setParams] = useState<ScreeningParams>(() => {
    const industryCode = searchParams.get("industryCode") ?? undefined;
    return {
      sortBy: "pe_ttm",
      sortDirection: "ASC",
      limit: 200,
      industryCode: industryCode || undefined,
    };
  });
  const [industries, setIndustries] = useState<IndustryValuation[]>([]);
  const [results, setResults] = useState<ScreeningStock[] | null>(null);
  const [watchlistCodes, setWatchlistCodes] = useState<Set<string>>(new Set());
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchValuationIndustries("pe").then(setIndustries).catch(() => {});
  }, []);

  // 登录后拉一次自选代码集合（⭐ 实心判断）；未登录保持空集合
  const reloadWatchlist = useCallback(() => {
    if (!user) {
      setWatchlistCodes(new Set());
      return;
    }
    fetchWatchlist()
      .then((rows) => setWatchlistCodes(new Set(rows.map((r) => r.stockCode))))
      .catch(() => {});
  }, [user]);

  useEffect(() => { reloadWatchlist(); }, [reloadWatchlist]);

  const submit = useCallback(async (next?: ScreeningParams) => {
    const p = next ?? params;
    const hasCondition = Object.entries(p).some(([k, v]) =>
      k !== "sortBy" && k !== "sortDirection" && k !== "limit" && v !== undefined && v !== null && v !== "");
    if (!hasCondition) { setError("请至少填写一个筛选条件"); setResults(null); return; }
    setLoading(true); setError(null);
    try { setResults(await fetchScreenedStocks(p)); }
    catch (e) { setError(e instanceof Error ? e.message : "筛选失败"); setResults(null); }
    finally { setLoading(false); }
  }, [params]);

  const updateParam = (key: keyof ScreeningParams, value: string) => {
    setParams((prev) => ({ ...prev, [key]: value === "" ? undefined : value }));
  };

  const onSort = (sortKey: string) => {
    const dir = params.sortBy === sortKey && params.sortDirection === "ASC" ? "DESC" : "ASC";
    const next = { ...params, sortBy: sortKey, sortDirection: dir as "ASC" | "DESC" };
    setParams(next);
    if (results) submit(next);
  };

  // 未登录点 ⭐ → 登录后回到 /screener；已登录 → 加入自选并更新集合
  const onToggleWatchlist = (stockCode: string) => {
    if (!user) {
      router.push("/login?redirect=/screener");
      return;
    }
    addToWatchlist(stockCode)
      .then(() => setWatchlistCodes((prev) => new Set(prev).add(stockCode)))
      .catch((e) => setError(e instanceof Error ? e.message : "加入自选失败"));
  };

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <h1 className="font-[family-name:var(--font-display)] text-2xl">价值筛选器</h1>

      <div className="flex gap-2 text-sm">
        <button
          data-testid="tab-screener"
          className={`rounded-md px-3 py-1.5 ${tab === "screener" ? "bg-[color:var(--color-panel)] text-[color:var(--color-ink)]" : "text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)]/60"}`}
          onClick={() => setTab("screener")}
        >
          筛选
        </button>
        <button
          data-testid="tab-watchlist"
          className={`rounded-md px-3 py-1.5 ${tab === "watchlist" ? "bg-[color:var(--color-panel)] text-[color:var(--color-ink)]" : "text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)]/60"}`}
          onClick={() => setTab("watchlist")}
        >
          自选
        </button>
      </div>

      {tab === "watchlist" ? (
        <WatchlistPanel />
      ) : (
        <>
          <ScreeningForm params={params} industries={industries} onChange={updateParam} onSubmit={() => submit()} loading={loading} />
          {error && <div className="text-sm text-[color:var(--color-up)]">{error}</div>}
          {results && (
            <div className="space-y-2">
              <div className="flex items-center justify-between">
                {params.indexCode && (
                  <div className="text-xs text-[color:var(--color-ink-faint)]">
                    指数范围：{INDEX_LABELS[params.indexCode] ?? params.indexCode} · 成分股每半年刷新
                  </div>
                )}
                <a
                  data-testid="export-csv"
                  href={buildExportHref(params)}
                  download
                  className="ml-auto rounded-lg border border-[color:var(--color-line)] px-3 py-1.5 text-sm text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)]"
                >
                  导出 CSV
                </a>
              </div>
              <ScreeningResultsTable results={results} sortBy={params.sortBy ?? "pe_ttm"}
                sortDirection={params.sortDirection ?? "ASC"} onSort={onSort}
                watchlistCodes={watchlistCodes} onToggleWatchlist={onToggleWatchlist} />
            </div>
          )}
        </>
      )}
      <Disclaimer />
    </div>
  );
}
