import { roundLabel } from "@/lib/fundingRounds";
import type { UnlistedOverview } from "@/lib/types";

/**
 * 行业全景卡（F06，库内派生）：四指标 + 近 12 月轮次分布迷你条形（纯 div，量小不上
 * ECharts）+ coverageNote 口径脚注（需求 NFR #1 口径诚实）。
 */
export default function UnlistedOverviewCard({ overview }: { overview: UnlistedOverview }) {
  const max = Math.max(1, ...overview.roundDistribution.map((d) => d.count));
  return (
    <div data-testid="unlisted-overview"
      className="rounded-2xl border border-[color:var(--color-line)] p-4 space-y-3">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        <div>
          <div className="text-xs text-[color:var(--color-ink-dim)]">上市公司</div>
          <div className="text-lg tabular">{overview.listedCount} 家</div>
        </div>
        <div>
          <div className="text-xs text-[color:var(--color-ink-dim)]">总市值（亿）</div>
          <div className="text-lg tabular">{overview.listedMarketCapYi.toLocaleString("zh-CN")}</div>
        </div>
        <div>
          <div className="text-xs text-[color:var(--color-ink-dim)]">策展头部企业</div>
          <div className="text-lg tabular">{overview.curatedCount} 家</div>
        </div>
        <div>
          <div className="text-xs text-[color:var(--color-ink-dim)]">近12月融资事件</div>
          <div className="text-lg tabular">{overview.fundingEvents12m} 起</div>
        </div>
      </div>
      {overview.roundDistribution.length > 0 && (
        <div className="flex flex-wrap items-end gap-4 pt-1" data-testid="round-distribution">
          {overview.roundDistribution.map((d) => (
            <div key={d.round} className="flex w-14 flex-col items-center gap-1">
              <div className="text-xs tabular text-[color:var(--color-ink-dim)]">{d.count}</div>
              <div className="w-6 rounded-sm bg-[color:var(--color-ink)]/70"
                style={{ height: `${Math.max(4, Math.round((d.count / max) * 32))}px` }} />
              <div className="text-[11px] text-[color:var(--color-ink-dim)]">{roundLabel(d.round)}</div>
            </div>
          ))}
        </div>
      )}
      <div className="text-xs text-[color:var(--color-ink-faint)]">* {overview.coverageNote}</div>
    </div>
  );
}
