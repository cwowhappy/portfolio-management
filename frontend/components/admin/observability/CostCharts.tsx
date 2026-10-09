"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildBarOption, buildLineOption } from "@/components/charts/optionBuilders";
import { adminApi, estimateCostCny, UNIT_PRICE_CNY_PER_MTOK, type CostAgg } from "@/lib/adminApi";

const WINDOW_OPTIONS = [7, 30, 90] as const;

/**
 * 观测区块②：成本看板（近 N 天）——按日 token 消耗折线 + 按工具调用条形。
 * 单价估算为前端本地乘法（unit-price-cny-per-mtok 无后端 GET，Task 7 传导裁定）。
 */
export default function CostCharts() {
  const [days, setDays] = useState<(typeof WINDOW_OPTIONS)[number]>(7);
  const [agg, setAgg] = useState<CostAgg | null>(null);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0); // 竞态守卫（沿 AnalyticsBoard.tsx:52-58）

  const load = useCallback((targetDays: number) => {
    const seq = ++requestSeqRef.current;
    adminApi
      .fetchCost(targetDays)
      .then((a) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的窗口请求，丢弃过期响应
        setAgg(a);
        setError(null);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载成本看板失败");
      });
  }, []);

  useEffect(() => {
    load(days);
  }, [load, days]);

  const empty = agg != null && agg.byDay.length === 0 && agg.byTool.length === 0;
  const estimate = agg != null ? estimateCostCny(agg) : null;

  const lineOption = useMemo(
    () =>
      agg && agg.byDay.length > 0
        ? buildLineOption({
            specVersion: 1,
            type: "line",
            title: "按日 token 消耗",
            categories: agg.byDay.map((d) => d.date),
            series: [
              { name: "输入", data: agg.byDay.map((d) => d.promptTokens) },
              { name: "输出", data: agg.byDay.map((d) => d.completionTokens) },
            ],
          })
        : null,
    [agg],
  );

  // 均时延折入类目标签（calls 与 ms 量级悬殊，不做同轴双系列；null=全无时长记录不加后缀）
  const barOption = useMemo(
    () =>
      agg && agg.byTool.length > 0
        ? buildBarOption({
            specVersion: 1,
            type: "bar",
            title: "按工具调用次数",
            horizontal: true,
            categories: agg.byTool.map((t) =>
              t.avgDurationMs != null ? `${t.tool}（均 ${Math.round(t.avgDurationMs)}ms）` : t.tool,
            ),
            series: [{ name: "调用次数", data: agg.byTool.map((t) => t.calls) }],
          })
        : null,
    [agg],
  );

  return (
    <section aria-label="成本看板" className="mt-8">
      <div className="flex flex-wrap items-center gap-3">
        <h3 className="text-[14px] font-medium text-[color:var(--color-ink-dim)]">成本看板</h3>
        <div className="flex gap-1">
          {WINDOW_OPTIONS.map((d) => (
            <button
              key={d}
              type="button"
              onClick={() => setDays(d)}
              className={
                d === days
                  ? "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-line-soft)] px-2.5 py-1 text-[11px] text-[color:var(--color-ink)]"
                  : "rounded-md border border-transparent px-2.5 py-1 text-[11px] text-[color:var(--color-ink-faint)] transition-all hover:text-[color:var(--color-ink-dim)]"
              }
            >
              近 {d} 天
            </button>
          ))}
        </div>
        {estimate != null && (
          <span className="rounded-full border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-2.5 py-0.5 text-[11px] text-[color:var(--color-ink-dim)]">
            估算 ¥{estimate.toFixed(4)}（单价 ¥{UNIT_PRICE_CNY_PER_MTOK}/Mtok）
          </span>
        )}
      </div>

      {error && (
        <p role="alert" className="mt-3 text-[13px] text-[color:var(--color-up)]">
          {error}
        </p>
      )}

      {empty ? (
        <p
          data-testid="cost-empty"
          className="mt-3 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-6 text-center text-[13px] text-[color:var(--color-ink-faint)]"
        >
          暂无成本观测：对话产生后自动采集
        </p>
      ) : (
        <div className="mt-3 grid gap-4 lg:grid-cols-2">
          {lineOption && <EChart option={lineOption} height={240} testid="cost-tokens-line" />}
          {barOption && <EChart option={barOption} height={240} testid="cost-tool-bar" />}
        </div>
      )}
    </section>
  );
}
