"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { fetchIndustryBoard } from "@/lib/industryApi";
import type { IndustryBoardItem } from "@/lib/types";
import IndustryBoardTable from "./IndustryBoardTable";
import Disclaimer from "@/components/Disclaimer";
import ValuationHeatmap from "./ValuationHeatmap";

export default function IndustryBoard() {
  const router = useRouter();
  const [items, setItems] = useState<IndustryBoardItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    fetchIndustryBoard()
      .then((d) => { if (!cancelled) setItems(d); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "加载失败"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  if (error) {
    return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;
  }
  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <h1 className="font-[family-name:var(--font-display)] text-2xl">行业估值</h1>
      {loading ? (
        <div className="h-40 rounded-2xl skeleton" aria-label="加载中" />
      ) : (
        <div className="grid md:grid-cols-2 gap-6">
          <IndustryBoardTable items={items} onSelect={(code) => router.push(`/industry/${code}`)} />
          <ValuationHeatmap industries={items} />
        </div>
      )}
      <Disclaimer />
    </div>
  );
}
