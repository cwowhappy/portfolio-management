import type { Concentration } from "@/lib/types";

// 集中度风险阈值（百分比），可配置
const CONCENTRATION_THRESHOLD_PCT = 20;

export default function ConcentrationList({ concentration }: { concentration: Concentration | null }) {
  const holdings = concentration?.holdings ?? [];
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">
        集中度分析
        {concentration && concentration.top5Ratio > CONCENTRATION_THRESHOLD_PCT && (
          <span className="ml-2 text-xs text-[color:var(--color-accent)]">前5大重仓占比超 {CONCENTRATION_THRESHOLD_PCT}%</span>
        )}
      </div>
      {holdings.length === 0 ? (
        <div className="text-sm text-[color:var(--color-ink-faint)]">暂无数据</div>
      ) : (
        <ul className="space-y-2">
          {holdings.map((h) => (
            <li key={h.stockCode} className="flex items-center justify-between text-sm">
              <span>{h.stockName}<span className="ml-2 text-xs text-[color:var(--color-ink-faint)]">{h.stockCode}</span></span>
              <span className="tabular">{h.ratio.toFixed(2)}%</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
