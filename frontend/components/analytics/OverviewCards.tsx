import type { AnalyticsOverview } from "@/lib/types";

function pct(n: number | null): string {
  return n == null ? "—" : `${(n * 100).toFixed(2)}%`;
}

function amt(n: number): string {
  return n.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** 收益总览卡：核心指标五卡 + 各基准同期收益/超额条。 */
export default function OverviewCards({ overview }: { overview: AnalyticsOverview }) {
  const cards = [
    { label: "总资产", value: amt(overview.totalValue) },
    { label: "TWR 累计", value: pct(overview.twrCumulative) },
    { label: "TWR 年化", value: pct(overview.twrAnnualized) },
    { label: "IRR", value: pct(overview.irr) },
    { label: "窗口天数", value: `${overview.windowDays}` },
  ];
  return (
    <div data-testid="analytics-overview" className="space-y-4">
      <div className="grid grid-cols-2 md:grid-cols-5 gap-4">
        {cards.map((c) => (
          <div key={c.label} className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 animate-rise">
            <div className="text-sm text-[color:var(--color-ink-dim)]">{c.label}</div>
            <div className="mt-2 text-2xl font-semibold tabular">{c.value}</div>
          </div>
        ))}
      </div>
      {Object.values(overview.benchmarks).length > 0 && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {Object.values(overview.benchmarks).map((b) => (
            <div key={b.indexCode} className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-4">
              <div className="text-sm text-[color:var(--color-ink-dim)]">{b.indexName}</div>
              <div className="mt-1 text-sm tabular">
                同期 <span className="text-[color:var(--color-ink)]">{pct(b.twr)}</span> · 超额{" "}
                <span className="text-[color:var(--color-ink)]">{pct(b.excess)}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
