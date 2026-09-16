"use client";

import { ASSET_CLASS_LABELS } from "@/lib/allocationApi";
import type { AssetClass, RebalanceItem, RebalanceView } from "@/lib/types";

function fmtAmount(v: number): string {
  return Math.abs(v).toLocaleString("zh-CN", { maximumFractionDigits: 0 });
}

function SuggestedCell({ v }: { v: number }) {
  if (Math.abs(v) < 0.005) {
    return <span className="text-[color:var(--color-ink-faint)]">— 持平</span>;
  }
  return v > 0
    ? <span className="text-[color:var(--color-up)]">买入 +{fmtAmount(v)}</span>
    : <span className="text-[color:var(--color-down)]">卖出 −{fmtAmount(v)}</span>;
}

function pct(v: number): string {
  return `${v.toFixed(2).replace(/\.00$/, "")}%`;
}

/** 再平衡卡：提醒横幅（阈值/时间触发明细）+ 逐类买卖金额建议 + ack 重置锚点。 */
export default function RebalanceCard({ view, onAck, ackBusy }: {
  view: RebalanceView | null;
  onAck: () => void;
  ackBusy?: boolean;
}) {
  if (!view || !view.hasActivePlan) {
    return (
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 text-sm text-[color:var(--color-ink-dim)]">
        再平衡：暂无生效方案，先在下方创建并激活方案
      </div>
    );
  }

  const breached = view.items.filter((i) => i.thresholdBreached);
  const timeDue = view.timeTrigger?.triggered === true;

  return (
    <div data-testid="rebalance-card" className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
      <div className="flex items-center justify-between">
        <div className="font-[family-name:var(--font-display)] text-[15px]">再平衡</div>
        {view.timeTrigger?.anchorDate && (
          <div className="text-xs text-[color:var(--color-ink-faint)]" data-testid="rebalance-anchor">
            上次再平衡 {new Date(view.timeTrigger.anchorDate).toLocaleDateString("zh-CN")}
          </div>
        )}
      </div>

      {view.suppressed ? (
        <div className="text-sm text-[color:var(--color-ink-dim)]">暂无资产（总资产为 0），无可再平衡</div>
      ) : (
        <>
          {view.anyAlert && (
            <div data-testid="rebalance-alert" className="rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-bg)]/60 px-4 py-3 text-sm space-y-1">
              {breached.length > 0 && (
                <div>
                  <span className="text-[color:var(--color-accent)]">偏离提醒：</span>
                  {breached.map((i: RebalanceItem) => `${ASSET_CLASS_LABELS[i.assetClass as AssetClass]} ${i.deviation > 0 ? "+" : ""}${i.deviation.toFixed(2)}pp`).join("；")}
                  <span className="text-[color:var(--color-ink-faint)]">（阈值 ±5pp）</span>
                </div>
              )}
              {timeDue && (
                <div>
                  <span className="text-[color:var(--color-accent)]">到期提醒：</span>
                  距上次再平衡已超期 {view.timeTrigger!.daysOverdue} 天（
                  {view.timeTrigger!.frequency === "QUARTERLY" ? "季度" : "半年"}周期）
                </div>
              )}
            </div>
          )}

          <div className="overflow-x-auto">
            <table className="w-full text-sm" data-testid="rebalance-table">
              <thead className="text-[color:var(--color-ink-dim)]">
                <tr>
                  <th className="text-left py-1">资产类别</th>
                  <th className="text-right py-1">目标</th>
                  <th className="text-right py-1">实际</th>
                  <th className="text-right py-1">偏离</th>
                  <th className="text-right py-1">建议</th>
                </tr>
              </thead>
              <tbody>
                {view.items.map((i) => (
                  <tr key={i.assetClass} data-testid={`rebalance-row-${i.assetClass}`} className="border-t border-[color:var(--color-line-soft)]">
                    <td className="py-1.5">
                      {ASSET_CLASS_LABELS[i.assetClass]}
                      {i.thresholdBreached && <span className="ml-1 text-[color:var(--color-accent)]">●</span>}
                    </td>
                    <td className="text-right">{pct(i.targetWeight)}</td>
                    <td className="text-right">{pct(i.actualWeight)}</td>
                    <td className={`text-right ${i.deviation >= 0 ? "text-[color:var(--color-up)]" : "text-[color:var(--color-down)]"}`}>
                      {i.deviation > 0 ? "+" : ""}{i.deviation.toFixed(2)}pp
                    </td>
                    <td className="text-right"><SuggestedCell v={i.suggestedAmount} /></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <div className="flex items-center justify-between">
            <details className="text-xs text-[color:var(--color-ink-faint)]">
              <summary className="cursor-pointer">口径说明</summary>
              <div className="mt-1 space-y-0.5">
                <div>· 阈值触发：任一类别 |实际−目标| ≥ 5 个百分点（固定口径）</div>
                <div>· 债券/黄金/REITs 持仓侧不可见（需场外配置），系统按 0 计算</div>
                <div>· 建议 = 目标金额 − 当前金额，各类合计恒为 0（总额守恒）</div>
                <div>· 时间提醒：{view.timeTrigger ? `周期 ${view.timeTrigger.frequency === "QUARTERLY" ? "季度(90天)" : view.timeTrigger.frequency === "SEMIANNUAL" ? "半年(180天)" : "关闭"}${view.timeTrigger.anchorDate ? `，上次再平衡 ${new Date(view.timeTrigger.anchorDate).toLocaleDateString("zh-CN")}` : ""}` : "未开启（在方案编辑中设置）"}</div>
              </div>
            </details>
            <button
              className="rounded-md bg-[color:var(--color-ink)] px-4 py-1.5 text-sm text-[color:var(--color-bg)] disabled:opacity-50"
              onClick={onAck}
              disabled={ackBusy}
            >
              已完成再平衡
            </button>
          </div>
        </>
      )}
    </div>
  );
}
