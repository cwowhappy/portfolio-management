import type { PortfolioOverview } from "@/lib/types";

function fmt(n: number | null): string {
  return n == null ? "—" : n.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

export default function OverviewCards({ overview }: { overview: PortfolioOverview }) {
  const cards = [
    { label: "总资产", value: overview.totalAssets },
    { label: "总成本", value: overview.totalCost },
    { label: "总盈亏", value: overview.totalPnl },
    { label: "今日盈亏", value: overview.todayPnl },
  ];
  return (
    <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {cards.map((c) => (
        <div key={c.label} className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 animate-rise">
          <div className="text-sm text-[color:var(--color-ink-dim)]">{c.label}</div>
          <div className="mt-2 text-2xl font-semibold tabular">{fmt(c.value)}</div>
        </div>
      ))}
    </div>
  );
}
