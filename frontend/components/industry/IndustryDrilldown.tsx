"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import { fetchIndustryBoard, fetchIndustryStocks } from "@/lib/industryApi";
import type { IndustryStock } from "@/lib/types";
import IndustryStockTable from "./IndustryStockTable";

const PROSPERITY_LABEL = { UP: "↑", FLAT: "→", DOWN: "↓" } as const;

// fetchIndustryStocks 的 sortBy 已收紧为字面量联合（02a4318），本组件状态/回调同口径收窄。
const SORT_KEYS = ["total_mv", "revenue", "roe"] as const;
type SortKey = (typeof SORT_KEYS)[number];
const isSortKey = (key: string): key is SortKey =>
  (SORT_KEYS as readonly string[]).includes(key);

export default function IndustryDrilldown({ industryCode }: { industryCode: string }) {
  const [stocks, setStocks] = useState<IndustryStock[]>([]);
  const [industryName, setIndustryName] = useState<string>("");
  const [prosperity, setProsperity] = useState<string>("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [sortBy, setSortBy] = useState<SortKey>("total_mv");
  const [dir, setDir] = useState<"ASC" | "DESC">("DESC");

  useEffect(() => {
    fetchIndustryBoard()
      .then((b) => {
        const row = b.find((i) => i.industryCode === industryCode);
        if (row) { setIndustryName(row.industryName); setProsperity(row.prosperity ?? ""); }
      })
      .catch(() => {}); // 页头增强信息，失败不阻断成员表
  }, [industryCode]);

  // 请求键变更即在渲染期置回加载态：React 官方「You Might Not Need an Effect」范式
  // （照 IndustryStockTable 排序守卫写法），避免 effect 内同步 setState 触发级联渲染
  // （react-hooks/set-state-in-effect）。
  const requestKey = `${industryCode}|${sortBy}|${dir}`;
  const [prevKey, setPrevKey] = useState(requestKey);
  if (prevKey !== requestKey) {
    setPrevKey(requestKey);
    setLoading(true);
  }

  useEffect(() => {
    let cancelled = false;
    fetchIndustryStocks(industryCode, { sortBy, sortDirection: dir })
      .then((s) => { if (!cancelled) setStocks(s); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "加载失败"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [industryCode, sortBy, dir]);

  const onSort = (key: string) => {
    if (!isSortKey(key)) return;
    const nextDir = sortBy === key && dir === "DESC" ? "ASC" : "DESC";
    setSortBy(key);
    setDir(nextDir);
  };

  if (error) {
    return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}
      <div className="mt-2"><Link href="/industry" className="underline">返回行业榜单</Link></div>
    </div>;
  }
  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <div className="flex items-baseline gap-3">
        <Link href="/industry" className="text-sm text-[color:var(--color-ink-dim)] hover:underline">← 行业榜单</Link>
        <h1 className="font-[family-name:var(--font-display)] text-2xl">
          {industryName || industryCode}{prosperity && <span className="ml-2 text-base">{PROSPERITY_LABEL[prosperity as keyof typeof PROSPERITY_LABEL]}</span>}
        </h1>
        <span className="text-sm text-[color:var(--color-ink-dim)]">成员 {stocks.length}</span>
      </div>
      {loading ? <div className="h-40 rounded-2xl skeleton" aria-label="加载中" /> : (
        <IndustryStockTable stocks={stocks} sortBy={sortBy} sortDirection={dir} onSort={onSort} />
      )}
    </div>
  );
}
