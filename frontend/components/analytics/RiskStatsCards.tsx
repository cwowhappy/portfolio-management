import type { ReactNode } from "react";
import type { RiskStatsView } from "@/lib/types";

// 后端数值一律 toPlainString 字符串（如 "0.2500000000"）：前端 parse 后按口径格式化；null=「—」。
function pct(s: string | null): string {
  return s == null ? "—" : `${(Number(s) * 100).toFixed(2)}%`;
}

function num(s: string | null): string {
  return s == null ? "—" : Number(s).toFixed(2);
}

function mddNote(stats: RiskStatsView): ReactNode {
  if (stats.recoveryDate != null) {
    return `${stats.peakDate}→${stats.troughDate}（${stats.drawdownDays} 交易日，${stats.recoveryDate} 恢复）`;
  }
  // recoveryDate null=回撤进行中（后端契约）：「—」独立成 span 节点，值与恢复位同形可整体断言；
  // troughDate null=窗口内从未回撤（单调新高），无峰谷区间可示，只标恢复状态。
  const range = stats.troughDate != null ? `${stats.peakDate}→${stats.troughDate}（${stats.drawdownDays} 交易日）· ` : "";
  return (
    <>
      {range}恢复 <span>—</span>（进行中）
    </>
  );
}

/** 风险指标卡（MS-13 F07/F08）：MDD/当前回撤/夏普/Calmar；sharpeRfFallback 标注 rf=0 退化口径。 */
export default function RiskStatsCards({ stats }: { stats: RiskStatsView }) {
  const cards: { label: string; value: string; note?: ReactNode }[] = [
    { label: "最大回撤", value: pct(stats.mdd), note: mddNote(stats) },
    { label: "当前回撤", value: pct(stats.currentDrawdown) },
    // rf 端口空表时后端退化 rf=0 计算夏普，小字标注口径
    { label: "夏普比率", value: num(stats.sharpe), note: stats.sharpeRfFallback ? "无 1Y 国债数据，rf=0 口径" : "rf=1Y 国债" },
    { label: "Calmar", value: num(stats.calmar) },
  ];
  return (
    <div data-testid="risk-stats" className="grid grid-cols-2 md:grid-cols-4 gap-4">
      {cards.map((c) => (
        <div key={c.label} className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 animate-rise">
          <div className="text-sm text-[color:var(--color-ink-dim)]">{c.label}</div>
          <div className="mt-2 text-2xl font-semibold tabular">{c.value}</div>
          {c.note && <div className="mt-1 text-xs text-[color:var(--color-ink-dim)]">{c.note}</div>}
        </div>
      ))}
    </div>
  );
}
