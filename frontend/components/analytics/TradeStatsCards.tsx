import type { TradeStatsView } from "@/lib/types";

// 后端数值一律 toPlainString 字符串（如 "0.5000"/"500.0000"）：前端 parse 后按口径格式化。
function pctStr(s: string): string {
  return `${(parseFloat(s) * 100).toFixed(2)}%`;
}

function amtStr(s: string): string {
  return parseFloat(s).toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

/** 交易统计卡：胜率/盈亏比/平均盈亏/持有天数等；profitFactor null（无亏损卖出）显示「—」。 */
export default function TradeStatsCards({ stats }: { stats: TradeStatsView }) {
  const cards = [
    { label: "了结卖出", value: `${stats.sellCount} 笔` },
    { label: "盈利笔数", value: `${stats.winCount} 笔` },
    { label: "胜率", value: pctStr(stats.winRate) },
    { label: "平均盈利", value: amtStr(stats.avgWin) },
    { label: "平均亏损", value: amtStr(stats.avgLoss) },
    { label: "盈亏比", value: stats.profitFactor == null ? "—" : parseFloat(stats.profitFactor).toFixed(2) },
    { label: "平均持有", value: `${parseFloat(stats.avgHoldingDays).toFixed(0)} 天` },
    { label: "最佳单笔", value: amtStr(stats.bestPnl) },
    { label: "最差单笔", value: amtStr(stats.worstPnl) },
  ];
  return (
    <div data-testid="trade-stats" className="grid grid-cols-2 md:grid-cols-3 gap-4">
      {cards.map((c) => (
        <div key={c.label} className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 animate-rise">
          <div className="text-sm text-[color:var(--color-ink-dim)]">{c.label}</div>
          <div className="mt-2 text-xl font-semibold tabular">{c.value}</div>
        </div>
      ))}
    </div>
  );
}
