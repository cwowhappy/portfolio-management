"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { fetchValuationIndustries } from "@/lib/valuationApi";
import type { IndustryValuation } from "@/lib/types";
import IndustryTable from "@/components/valuation/IndustryTable";
import Disclaimer from "@/components/Disclaimer";
import ValuationHeatmap from "./ValuationHeatmap";

export default function IndustryBoard() {
  const router = useRouter();
  const [industries, setIndustries] = useState<IndustryValuation[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchValuationIndustries("pe")
      .then((d) => { if (!cancelled) setIndustries(d); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "加载失败"); });
    return () => { cancelled = true; };
  }, []);

  if (error) {
    return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;
  }
  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <h1 className="font-[family-name:var(--font-display)] text-2xl">行业估值</h1>
      <div className="grid md:grid-cols-2 gap-6">
        <IndustryTable industries={industries} onSelect={(code) => router.push(`/screener?industryCode=${code}`)} />
        <ValuationHeatmap industries={industries} />
      </div>
      <Disclaimer />
    </div>
  );
}
