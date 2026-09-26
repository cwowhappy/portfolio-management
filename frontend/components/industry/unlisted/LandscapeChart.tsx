"use client";

import { useEffect, useMemo, useState } from "react";
import { useRouter } from "next/navigation";
import { fetchIndustryStocks } from "@/lib/industryApi";
import { fetchUnlistedCompanies } from "@/lib/industryUnlistedApi";
import { FUNDING_ROUNDS } from "@/lib/fundingRounds";
import { getPalette } from "@/lib/chart-theme";
import type { ECOption } from "@/lib/echarts-setup";
import { buildScatterOption, type LandscapeSpec } from "@/components/charts/optionBuilders";
import { EChart } from "@/components/charts/EChart";

/**
 * 竞争格局气泡图（F09）：前端组合既有成员股（total_mv 前 30）+ 策展名单两公开端点，
 * 不新增后端聚合端点（设计规格 §九#5）。上市气泡点击跳行情台（M04 联动）。
 */
export default function LandscapeChart({ industryCode }: { industryCode: string }) {
  const router = useRouter();
  const [option, setOption] = useState<ECOption | null>(null);
  const [error, setError] = useState<string | null>(null);

  // 请求键变更渲染期置回骨架屏（同 UnlistedPanel，避开 set-state-in-effect）
  const [prevCode, setPrevCode] = useState(industryCode);
  if (prevCode !== industryCode) {
    setPrevCode(industryCode);
    setOption(null);
    setError(null);
  }

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      fetchIndustryStocks(industryCode, { sortBy: "total_mv", limit: 30 }),
      fetchUnlistedCompanies(industryCode),
    ])
      .then(([stocks, companies]) => {
        if (cancelled) return;
        const spec: LandscapeSpec = {
          kind: "landscape",
          listed: stocks
            .filter((s) => s.totalMv != null)
            .map((s) => ({ name: s.stockName, code: s.stockCode, marketCapYi: s.totalMv! / 1e8 })),
          unlisted: companies.map((c) => ({
            name: c.companyName,
            round: c.latestRoundLabel,
            roundOrder: FUNDING_ROUNDS.findIndex((r) => r.value === c.latestRound),
          })),
        };
        const p = getPalette();
        setOption(buildScatterOption(spec, [p.accent, p.up]));
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "加载失败");
      });
    return () => { cancelled = true; };
  }, [industryCode]);

  // 引用稳定（useECharts onEvents 依赖）：router 引用变更时重建
  const onEvents = useMemo(() => ({
    click: (params: unknown) => {
      const data = (params as { data?: { code?: string } }).data;
      if (data?.code) router.push(`/market?code=${data.code}`);
    },
  }), [router]);

  if (error) {
    return <div className="text-sm text-[color:var(--color-ink-dim)]">竞争格局加载失败：{error}</div>;
  }
  return (
    <section className="space-y-2" aria-label="竞争格局">
      <h2 className="font-[family-name:var(--font-display)] text-base">竞争格局</h2>
      {option
        ? <EChart option={option} testid="landscape-chart" height={280} onEvents={onEvents} />
        : <div className="h-[280px] rounded-2xl skeleton" aria-label="加载中" />}
    </section>
  );
}
