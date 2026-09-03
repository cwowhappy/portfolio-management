"use client";

import { useEffect, useState } from "react";
import { fetchValuationOverview, fetchValuationHistory, fetchValuationIndustries } from "@/lib/valuationApi";
import type { ValuationOverview, ValuationHistory, IndustryValuation } from "@/lib/types";
import StatCard from "./StatCard";
import IndexValuationTable from "./IndexValuationTable";
import Thermometer from "./Thermometer";
import TrendChart from "./TrendChart";
import IndustryTable from "./IndustryTable";
import Disclaimer from "@/components/Disclaimer";

export default function ValuationBoard() {
  const [overview, setOverview] = useState<ValuationOverview | null>(null);
  const [history, setHistory] = useState<ValuationHistory | null>(null);
  const [industries, setIndustries] = useState<IndustryValuation[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [o, h, ind] = await Promise.all([
          fetchValuationOverview(),
          fetchValuationHistory(),
          fetchValuationIndustries("pe"),
        ]);
        if (cancelled) return;
        setOverview(o);
        setHistory(h);
        setIndustries(ind);
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : "加载失败");
      }
    })();
    return () => { cancelled = true; };
  }, []);

  if (error) {
    return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;
  }
  if (!overview) {
    return <div className="p-8 skeleton h-40 rounded-2xl" />;
  }
  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">市场估值仪表盘</h1>
        {overview.dataAccumulating && (
          <span className="text-xs text-[color:var(--color-accent)]">数据积累中 · 分位仅供参考</span>
        )}
      </div>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4" data-testid="stat-grid">
        <StatCard title="全A PE 中位数" value={overview.latestSnapshot?.peMedian ?? null} percentile={overview.pePercentile} />
        <StatCard title="全A PB 中位数" value={overview.latestSnapshot?.pbMedian ?? null} percentile={overview.pbPercentile} />
        <StatCard title="破净股占比" value={overview.latestSnapshot ? Number((overview.latestSnapshot.netBreakerRatio * 100).toFixed(2)) : null} unit="%" percentile={overview.netBreakerPercentile} />
        <StatCard title="股债利差 (ERP)" value={overview.erp} unit="%" percentile={overview.erpPercentile} />
      </div>
      <div className="mt-6">
        <IndexValuationTable indices={overview.indices} />
      </div>
      <div className="grid md:grid-cols-2 gap-6" data-testid="charts">
        <Thermometer value={overview.thermometer} />
        <TrendChart snapshots={history?.snapshots ?? []} />
      </div>
      <div className="mt-6" data-testid="industries">
        <IndustryTable industries={industries} />
      </div>
      <Disclaimer />
    </div>
  );
}
