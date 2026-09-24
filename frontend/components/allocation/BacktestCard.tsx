"use client";

import { useEffect, useMemo, useState } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildLineOption } from "@/components/charts/optionBuilders";
import { fetchBacktest, fetchPlans, fetchTemplates } from "@/lib/allocationApi";
import type { BacktestView, PlanView, TemplateView } from "@/lib/types";

const WINDOW_OPTIONS = [
  { value: "3Y", label: "3 年" },
  { value: "5Y", label: "5 年" },
  { value: "MAX", label: "最长" },
];
const REBALANCE_OPTIONS = [
  { value: "never", label: "永不" },
  { value: "quarterly", label: "季度" },
  { value: "annual", label: "年度" },
];

// 与 RiskStatsCards 同口径：后端 toPlainString 小数 → 两位百分比/比率
function pct(s: string): string {
  return `${(Number(s) * 100).toFixed(2)}%`;
}

/** 配置回测卡（MS-13 M07-F06）：方案/模板/窗口/再平衡表单（plans+templates 自取填下拉），
 * 提交调 fetchBacktest；结果区三指标 + 单序列净值曲线（标题在卡内渲染——buildLineOption 弃 spec.title）。
 * 422（REITs 无数据源）等错误按 http 层 Error.message 展示在卡内，不崩卡片。 */
export default function BacktestCard() {
  const [plans, setPlans] = useState<PlanView[]>([]);
  const [templates, setTemplates] = useState<TemplateView[]>([]);
  const [form, setForm] = useState({ planId: "", template: "", window: "5Y", rebalance: "never" });
  const [result, setResult] = useState<BacktestView | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    Promise.all([fetchPlans(), fetchTemplates()])
      .then(([p, t]) => { setPlans(p); setTemplates(t); })
      .catch((e) => setError(e instanceof Error ? e.message : "下拉数据加载失败")); // 表单仍可用（默认参数不依赖下拉）
  }, []);

  const option = useMemo(
    () =>
      result
        ? buildLineOption({
            specVersion: 1,
            type: "line",
            title: "回测净值曲线",
            categories: result.curve.map((c) => c.date),
            series: [{ name: result.planName, data: result.curve.map((c) => Number(c.value)) }],
          })
        : null,
    [result],
  );

  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      const r = await fetchBacktest({
        ...(form.planId ? { planId: Number(form.planId) } : {}),
        ...(form.template ? { template: form.template } : {}),
        window: form.window,
        rebalance: form.rebalance,
      });
      setResult(r);
    } catch (e) {
      setError(e instanceof Error ? e.message : "回测失败");
    } finally {
      setBusy(false);
    }
  };

  const selectCls = "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg)] px-2 py-1";

  return (
    <div data-testid="backtest-card" className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-3">
      <div className="font-[family-name:var(--font-display)] text-[15px]">配置回测</div>

      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 text-sm">
        <label htmlFor="backtest-plan" className="text-[color:var(--color-ink-dim)]">方案</label>
        <select id="backtest-plan" className={selectCls} value={form.planId}
          onChange={(e) => setForm({ ...form, planId: e.target.value })}>
          <option value="">跟随生效方案</option>
          {plans.map((p) => <option key={p.id} value={String(p.id)}>{p.name}</option>)}
        </select>
        <label htmlFor="backtest-template" className="text-[color:var(--color-ink-dim)]">模板</label>
        <select id="backtest-template" className={selectCls} value={form.template}
          onChange={(e) => setForm({ ...form, template: e.target.value })}>
          <option value="">不指定</option>
          {templates.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
        <label htmlFor="backtest-window" className="text-[color:var(--color-ink-dim)]">窗口</label>
        <select id="backtest-window" className={selectCls} value={form.window}
          onChange={(e) => setForm({ ...form, window: e.target.value })}>
          {WINDOW_OPTIONS.map((w) => <option key={w.value} value={w.value}>{w.label}</option>)}
        </select>
        <label htmlFor="backtest-rebalance" className="text-[color:var(--color-ink-dim)]">再平衡</label>
        <select id="backtest-rebalance" className={selectCls} value={form.rebalance}
          onChange={(e) => setForm({ ...form, rebalance: e.target.value })}>
          {REBALANCE_OPTIONS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
        </select>
        <button
          className="rounded-md bg-[color:var(--color-ink)] px-4 py-1.5 text-[color:var(--color-bg)] disabled:opacity-50"
          onClick={run}
          disabled={busy}
        >
          {busy ? "计算中…" : "运行回测"}
        </button>
      </div>

      {error && <div className="text-sm text-[color:var(--color-down)]">{error}</div>}

      {result && (
        <div className="space-y-3">
          <div className="text-xs text-[color:var(--color-ink-faint)]">
            {result.planName} · {result.windowStart} ~ {result.windowEnd} · 窗口 {result.window} · 再平衡 {result.rebalance}
          </div>
          <div className="grid grid-cols-3 gap-4">
            <div className="rounded-xl border border-[color:var(--color-line-soft)] px-4 py-3">
              <div className="text-sm text-[color:var(--color-ink-dim)]">年化收益</div>
              <div className="mt-2 text-2xl font-semibold tabular">{pct(result.annualizedReturn)}</div>
            </div>
            <div className="rounded-xl border border-[color:var(--color-line-soft)] px-4 py-3">
              <div className="text-sm text-[color:var(--color-ink-dim)]">最大回撤</div>
              <div className="mt-2 text-2xl font-semibold tabular">{pct(result.mdd)}</div>
            </div>
            <div className="rounded-xl border border-[color:var(--color-line-soft)] px-4 py-3">
              <div className="text-sm text-[color:var(--color-ink-dim)]">夏普比率</div>
              <div className="mt-2 text-2xl font-semibold tabular">
                {result.sharpe == null ? "—" : Number(result.sharpe).toFixed(2)}
              </div>
              {result.rfFallback && (
                <div className="mt-1 text-xs text-[color:var(--color-ink-dim)]">无 1Y 国债数据，夏普按 rf=0 口径</div>
              )}
            </div>
          </div>
          {option && <EChart option={option} height={240} testid="backtest-chart" />}
        </div>
      )}
    </div>
  );
}
