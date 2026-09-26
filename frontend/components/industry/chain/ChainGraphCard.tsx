"use client";

import { useMemo } from "react";
import { useRouter } from "next/navigation";
import { getPalette } from "@/lib/chart-theme";
import { buildGraphOption, type ChainGraphSpec } from "@/components/charts/optionBuilders";
import { EChart } from "@/components/charts/EChart";
import type { ChainView } from "@/lib/types";

/**
 * 产业链图卡（F11）：ChainView → ChainGraphSpec → 力导向图。tier→category 三色
 * （运行时 getPalette 解析，缺省回退构建器内置字面值口径由传参保证）；上市节点
 * 点击跳行情台（M04 联动，onEvents 引用稳定照 LandscapeChart 先例）。
 */
export default function ChainGraphCard({ chain }: { chain: ChainView }) {
  const router = useRouter();

  const spec = useMemo<ChainGraphSpec>(() => {
    const tierOf = (t: string): ChainGraphSpec["nodes"][number]["tier"] =>
      t === "UPSTREAM" || t === "MIDSTREAM" ? t : "DOWNSTREAM"; // 后端枚举镜像兜底
    return {
      kind: "chain",
      name: chain.name,
      nodes: chain.stages.flatMap((s) =>
        s.members.map((m) => ({
          id: String(m.id),
          name: m.displayName,
          tier: tierOf(s.tier),
          listed: m.memberType === "LISTED",
          stockCode: m.stockCode ?? undefined,
          sub: `${s.name}·${s.tierLabel}`,
        }))),
    };
  }, [chain]);

  const option = useMemo(() => {
    const p = getPalette();
    return buildGraphOption(spec, [p.accent, p.up, p.inkDim]);
  }, [spec]);

  // 引用稳定（useECharts onEvents 依赖）：router 引用变更时重建
  const onEvents = useMemo(() => ({
    click: (params: unknown) => {
      const data = (params as { data?: { stockCode?: string } }).data;
      if (data?.stockCode) router.push(`/market?code=${data.stockCode}`);
    },
  }), [router]);

  return (
    <section data-testid={`chain-card-${chain.id}`} className="space-y-2" aria-label={`产业链 ${chain.name}`}>
      <div className="flex items-baseline gap-2">
        <h2 className="font-[family-name:var(--font-display)] text-base">{chain.name}</h2>
        {chain.description && (
          <span className="text-xs text-[color:var(--color-ink-dim)]">{chain.description}</span>
        )}
      </div>
      <EChart option={option} testid={`chain-graph-${chain.id}`} height={320} onEvents={onEvents} />
    </section>
  );
}
