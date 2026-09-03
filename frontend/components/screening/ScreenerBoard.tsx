"use client";

import { useCallback, useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { fetchScreenedStocks } from "@/lib/screeningApi";
import { fetchValuationIndustries } from "@/lib/valuationApi";
import type { IndustryValuation, ScreeningParams, ScreeningStock } from "@/lib/types";
import Disclaimer from "@/components/Disclaimer";
import ScreeningForm from "./ScreeningForm";
import ScreeningResultsTable from "./ScreeningResultsTable";

export default function ScreenerBoard() {
  // 行业页跳转「/screener?industryCode=…」带入行业条件：挂载时读取一次并初始化到 params，
  // 避免行业条件丢失（否则提交会误报「请至少填写一个筛选条件」）。
  const searchParams = useSearchParams();
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
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    fetchValuationIndustries("pe").then(setIndustries).catch(() => {});
  }, []);

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

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <h1 className="font-[family-name:var(--font-display)] text-2xl">价值筛选器</h1>
      <ScreeningForm params={params} industries={industries} onChange={updateParam} onSubmit={() => submit()} loading={loading} />
      {error && <div className="text-sm text-[color:var(--color-up)]">{error}</div>}
      {results && (
        <ScreeningResultsTable results={results} sortBy={params.sortBy ?? "pe_ttm"}
          sortDirection={params.sortDirection ?? "ASC"} onSort={onSort} />
      )}
      <Disclaimer />
    </div>
  );
}
