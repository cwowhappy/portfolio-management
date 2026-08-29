import type { PositionView } from "@/lib/types";

function fmt(n: number | null): string {
  return n == null ? "—" : n.toFixed(2);
}

export default function PositionTable({ positions }: { positions: PositionView[] }) {
  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 overflow-x-auto">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">持仓列表</div>
      <table className="w-full text-sm">
        <thead className="text-[color:var(--color-ink-dim)]">
          <tr>
            <th className="text-left py-1">名称/代码</th>
            <th className="text-right py-1">数量</th>
            <th className="text-right py-1">成本价</th>
            <th className="text-right py-1">现价</th>
            <th className="text-right py-1">市值</th>
            <th className="text-right py-1">盈亏</th>
            <th className="text-right py-1">收益率</th>
          </tr>
        </thead>
        <tbody className="tabular">
          {positions.map((p) => (
            <tr key={p.id} className="border-t border-[color:var(--color-line-soft)]">
              <td className="py-2">{p.stockName}<span className="ml-2 text-xs text-[color:var(--color-ink-faint)]">{p.stockCode}</span></td>
              <td className="text-right">{p.quantity}</td>
              <td className="text-right">{fmt(p.avgCost)}</td>
              <td className="text-right">{fmt(p.price)}</td>
              <td className="text-right">{fmt(p.marketValue)}</td>
              <td className="text-right">{fmt(p.floatingPnl)}</td>
              <td className="text-right">{p.pnlRatio == null ? "—" : `${p.pnlRatio.toFixed(2)}%`}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {positions.length === 0 && <div className="py-6 text-center text-sm text-[color:var(--color-ink-faint)]">暂无持仓，点击「买入」开始记录</div>}
    </div>
  );
}
