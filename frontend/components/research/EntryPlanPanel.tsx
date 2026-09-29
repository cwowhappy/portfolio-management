"use client";

import { useCallback, useEffect, useState } from "react";
import ChecklistConfirmCard from "./ChecklistConfirmCard";
import {
  getEntryPlan,
  previewCheck,
  saveEntryPlan,
  submitCheck,
  type SaveEntryBatchItemInput,
} from "@/lib/researchApi";
import { useSaveAction } from "@/lib/useSaveAction";
import {
  CHECK_TYPE_LABELS,
  F01_MUST_ITEMS,
  type CheckItemResult,
  type CheckType,
  type EntryBatchView,
  type EntryPlanView,
} from "@/lib/researchSchemas";

// 建仓计划面板（F09，D23）+ 纪律检查发起（F10/F12，D18 页面级确认流）：
// - 计划 GET 404（「建仓计划不存在」）→ 未保存空表单；PUT 整替保存（后端 Σratio>1 → 422 双保险，
//   前端 Σ>1 拦截不发请求）；kellyRatio 为后端读时算得，只读展示不重复实现。
// - 检查入口：买入（BUY）挂批次行、卖出/减仓（SELL/REDUCE）挂持仓操作区——不进 portfolio
//   交易页（D18：检查单与交易录入解耦，避免「录交易被卡/检查了没录」歧义）。
// - 流程：F01 四项勾选 → 预检（preview 纯读不落库）→ ChecklistConfirmCard 确认/越过 →
//   submit 留痕（append-only + journal 事件）。表单预填随读模型 updatedAt 渲染期重置
//   （照 StrategyPanel 排序守卫写法）。

interface BatchRow {
  seq: number;
  priceLow: string;
  priceHigh: string;
  quantity: string;
  amount: string;
  ratio: string;
}

interface CheckFlow {
  checkType: CheckType;
  items: CheckItemResult[] | null;
}

const emptyRow = (seq: number): BatchRow => ({
  seq, priceLow: "", priceHigh: "", quantity: "", amount: "", ratio: "",
});

const toRow = (b: EntryBatchView): BatchRow => ({
  seq: b.seq,
  priceLow: String(b.priceLow),
  priceHigh: String(b.priceHigh),
  quantity: String(b.quantity),
  amount: b.amount == null ? "" : String(b.amount),
  ratio: String(b.ratio),
});

/** 空串/非数 → null；浮点 Σratio 比较用 epsilon（0.6+0.4 类累加误差容忍）。 */
const num = (v: string): number | null => {
  const t = v.trim();
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
};

const RATIO_EPSILON = 1e-9;
/** 展示用四舍五入（Σ 0.6+0.5 之类浮点尾差不外漏）。 */
const fmtRatio = (n: number) => String(Math.round(n * 10000) / 10000);

const inputCls = "rounded-md border border-[color:var(--color-line)] px-2.5 py-1.5 text-sm";
const labelCls = "block text-xs text-[color:var(--color-ink-faint)] mb-1";
const btnPrimary =
  "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)] disabled:opacity-60";
const btnGhost =
  "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)] text-[color:var(--color-ink-dim)] disabled:opacity-60";
const btnDanger =
  "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-down)]/50 text-[color:var(--color-down)] disabled:opacity-60";

export default function EntryPlanPanel({ projectId, onChanged }: {
  projectId: number;
  onChanged: () => void;
}) {
  const [plan, setPlan] = useState<EntryPlanView | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [winRate, setWinRate] = useState("");
  const [payoff, setPayoff] = useState("");
  const [rows, setRows] = useState<BatchRow[]>(() => [emptyRow(1)]);
  const planSave = useSaveAction("保存建仓计划失败");
  // 纪律检查流（D18）
  const [flow, setFlow] = useState<CheckFlow | null>(null);
  const [f01, setF01] = useState<Record<string, boolean>>({});
  const previewSave = useSaveAction("预检失败");

  const loadPlan = useCallback(() => {
    getEntryPlan(projectId)
      .then((p) => {
        setPlan(p);
        setLoadError(null);
      })
      .catch((e) => {
        // 未保存 → 404「建仓计划不存在」：视同空表单（照 strategy=null 先例），不报错
        if (e instanceof Error && e.message.includes("建仓计划不存在")) {
          setPlan(null);
          setLoadError(null);
        } else {
          setLoadError(e instanceof Error ? e.message : "加载失败");
        }
      });
  }, [projectId]);

  useEffect(() => {
    loadPlan();
  }, [loadPlan]);

  // 读模型变更（保存成功 setPlan / 首次加载）→ 渲染期重置表单
  const seed = plan?.updatedAt ?? "unsaved";
  const [prevSeed, setPrevSeed] = useState(seed);
  if (prevSeed !== seed) {
    setPrevSeed(seed);
    setRows(plan ? plan.batches.map(toRow) : [emptyRow(1)]);
    setWinRate(plan?.winRate == null ? "" : String(plan.winRate));
    setPayoff(plan?.payoffRatio == null ? "" : String(plan.payoffRatio));
  }

  // Σratio 实时（仅累计可解析占比；行内非法值由保存校验拦截）
  const ratioSum = rows.reduce((sum, r) => {
    const n = num(r.ratio);
    return n == null ? sum : sum + n;
  }, 0);
  const sumOver = ratioSum > 1 + RATIO_EPSILON;

  const updateRow = (i: number, patch: Partial<BatchRow>) => {
    setRows((rs) => rs.map((r, idx) => (idx === i ? { ...r, ...patch } : r)));
  };
  const addRow = () => setRows((rs) => [...rs, emptyRow(rs.length + 1)]);
  const removeRow = (i: number) =>
    setRows((rs) => rs.filter((_, idx) => idx !== i).map((r, idx) => ({ ...r, seq: idx + 1 })));

  const savePlan = () => {
    // 前端预校验镜像后端 EntryBatch 域校验（400/422），拦截在发请求之前
    const batches: SaveEntryBatchItemInput[] = [];
    for (const r of rows) {
      const lo = num(r.priceLow);
      const hi = num(r.priceHigh);
      const qty = num(r.quantity);
      const ratio = num(r.ratio);
      const amount = num(r.amount);
      if (lo == null || hi == null || lo < 0 || hi < 0) return planSave.setError("批次价格区间须为非负数值");
      if (lo > hi) return planSave.setError("批次价格下限不能高于上限");
      if (qty == null || !Number.isInteger(qty) || qty <= 0) {
        return planSave.setError("批次数量必须为正整数");
      }
      if (ratio == null || ratio <= 0 || ratio > 1) {
        return planSave.setError("批次仓位占比须在 (0,1] 区间");
      }
      if (r.amount.trim() !== "" && (amount == null || amount < 0)) {
        return planSave.setError("批次金额须为非负数值");
      }
      batches.push({ seq: r.seq, priceLow: lo, priceHigh: hi, quantity: qty, amount: amount ?? null, ratio });
    }
    if (sumOver) return planSave.setError("批次占比合计超过 100%，请调整后再保存");
    void planSave.run(async () => {
      const saved = await saveEntryPlan(projectId, {
        winRate: num(winRate),
        payoffRatio: num(payoff),
        batches,
      });
      setPlan(saved);
    });
  };

  const startCheck = (checkType: CheckType) => {
    previewSave.reset();
    setF01({});
    setFlow({ checkType, items: null });
  };

  const runPreview = () => {
    if (!flow) return;
    const checkType = flow.checkType;
    void previewSave.run(async () => {
      const items = await previewCheck(projectId, {
        checkType,
        f01MustItems: Object.fromEntries(F01_MUST_ITEMS.map((k) => [k, f01[k] === true])),
      });
      setFlow((f) => (f ? { ...f, items } : f));
    });
  };

  /** 确认卡提交：items 为 preview 快照原样回传（留痕定格）；成功后收起并通知父级重载。 */
  const submitFromCard = async (result: "CONFIRMED" | "OVERRIDDEN", reason?: string) => {
    if (!flow?.items) return;
    await submitCheck(projectId, { checkType: flow.checkType, result, overrideReason: reason, items: flow.items });
    setFlow(null);
    onChanged();
  };

  return (
    <section className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-4">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="font-[family-name:var(--font-display)] text-[15px]">建仓计划</h2>
        {plan?.kellyRatio != null && (
          <span className="rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[11px] text-[color:var(--color-ink-dim)]">
            凯利比例 {fmtRatio(plan.kellyRatio)}
          </span>
        )}
        {plan == null && !loadError && (
          <span className="text-xs text-[color:var(--color-ink-faint)]">尚未保存建仓计划——填写批次后保存</span>
        )}
      </div>

      {loadError && <div className="text-sm text-[color:var(--color-down)]">{loadError}</div>}

      <div className="grid gap-3 sm:grid-cols-2">
        <div>
          <label className={labelCls} htmlFor="plan-win-rate">胜率（0~1，可选）</label>
          <input
            id="plan-win-rate" aria-label="胜率" type="number" step="any" min="0" max="1"
            className={`${inputCls} w-full`} value={winRate}
            onChange={(e) => setWinRate(e.target.value)}
          />
        </div>
        <div>
          <label className={labelCls} htmlFor="plan-payoff">赔率（可选）</label>
          <input
            id="plan-payoff" aria-label="赔率" type="number" step="any" min="0"
            className={`${inputCls} w-full`} value={payoff}
            onChange={(e) => setPayoff(e.target.value)}
          />
        </div>
      </div>
      <p className="text-xs text-[color:var(--color-ink-faint)]">
        胜率/赔率均填写并保存后，按简化凯利公式只做算术参考（D23）；kellyRatio 由服务端计算。
      </p>

      <ul className="space-y-2">
        {rows.map((r, i) => (
          <li key={r.seq} className="flex flex-wrap items-end gap-2">
            <span className="w-6 pb-2 text-center text-xs text-[color:var(--color-ink-faint)]">{r.seq}</span>
            <div>
              <label className={labelCls} htmlFor={`batch-low-${r.seq}`}>价格下限</label>
              <input
                id={`batch-low-${r.seq}`} aria-label={`批次 ${r.seq} 价格下限`}
                type="number" step="any" min="0" className={inputCls} value={r.priceLow}
                onChange={(e) => updateRow(i, { priceLow: e.target.value })}
              />
            </div>
            <div>
              <label className={labelCls} htmlFor={`batch-high-${r.seq}`}>价格上限</label>
              <input
                id={`batch-high-${r.seq}`} aria-label={`批次 ${r.seq} 价格上限`}
                type="number" step="any" min="0" className={inputCls} value={r.priceHigh}
                onChange={(e) => updateRow(i, { priceHigh: e.target.value })}
              />
            </div>
            <div>
              <label className={labelCls} htmlFor={`batch-qty-${r.seq}`}>数量（股）</label>
              <input
                id={`batch-qty-${r.seq}`} aria-label={`批次 ${r.seq} 数量`}
                type="number" step="1" min="1" className={inputCls} value={r.quantity}
                onChange={(e) => updateRow(i, { quantity: e.target.value })}
              />
            </div>
            <div>
              <label className={labelCls} htmlFor={`batch-amount-${r.seq}`}>金额（可空）</label>
              <input
                id={`batch-amount-${r.seq}`} aria-label={`批次 ${r.seq} 金额`}
                type="number" step="any" min="0" className={inputCls} value={r.amount}
                onChange={(e) => updateRow(i, { amount: e.target.value })}
              />
            </div>
            <div>
              <label className={labelCls} htmlFor={`batch-ratio-${r.seq}`}>仓位占比</label>
              <input
                id={`batch-ratio-${r.seq}`} aria-label={`批次 ${r.seq} 占比`}
                type="number" step="any" min="0" max="1" className={inputCls} value={r.ratio}
                onChange={(e) => updateRow(i, { ratio: e.target.value })}
              />
            </div>
            <button
              type="button" className={btnDanger}
              aria-label={`批次 ${r.seq} 发起买入检查`}
              onClick={() => startCheck("BUY")}
            >
              发起买入检查
            </button>
            <button
              type="button" className={btnGhost} aria-label={`删除批次 ${r.seq}`}
              onClick={() => removeRow(i)}
            >
              删除
            </button>
          </li>
        ))}
      </ul>

      <div className="flex flex-wrap items-center gap-2">
        <span
          className={`text-sm ${sumOver ? "text-[color:var(--color-down)]" : "text-[color:var(--color-ink-dim)]"}`}
        >
          Σ占比 {fmtRatio(ratioSum)}
        </span>
        {sumOver && (
          <span className="text-sm text-[color:var(--color-down)]">
            批次占比合计超过 100%，请调整后再保存
          </span>
        )}
        <button type="button" className={btnGhost} onClick={addRow}>添加批次</button>
        <button type="button" className={btnPrimary} disabled={planSave.saving} onClick={savePlan}>
          保存建仓计划
        </button>
      </div>
      {planSave.error && <div className="text-sm text-[color:var(--color-down)]">{planSave.error}</div>}

      {/* 持仓操作区：卖出/减仓意图发起检查（D18——不进 portfolio 交易页） */}
      <div className="space-y-2 border-t border-[color:var(--color-line-soft)] pt-4">
        <h3 className="text-sm font-medium text-[color:var(--color-ink)]">持仓纪律检查</h3>
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" className={btnGhost} onClick={() => startCheck("SELL")}>卖出检查</button>
          <button type="button" className={btnGhost} onClick={() => startCheck("REDUCE")}>减仓检查</button>
          <span className="text-xs text-[color:var(--color-ink-faint)]">
            从卖出/减仓意图主动发起；检查为软提醒不阻断（D5）
          </span>
        </div>
      </div>

      {flow && (
        <div className="space-y-3 rounded-xl border border-[color:var(--color-line-soft)] p-4">
          <h3 className="text-sm font-medium text-[color:var(--color-ink)]">
            发起{CHECK_TYPE_LABELS[flow.checkType]}纪律检查
          </h3>
          <div className="flex flex-wrap gap-x-5 gap-y-1.5">
            {F01_MUST_ITEMS.map((k) => (
              <label key={k} className="flex items-center gap-1.5 text-sm text-[color:var(--color-ink-dim)]">
                <input
                  type="checkbox" aria-label={k} checked={f01[k] === true}
                  onChange={(e) => setF01((m) => ({ ...m, [k]: e.target.checked }))}
                />
                {k}
              </label>
            ))}
          </div>
          <p className="text-xs text-[color:var(--color-ink-faint)]">
            F01 必查项：勾选=已确认，未勾选将记为命中；SELL/REDUCE 会追加证伪条件核对项。
          </p>
          {previewSave.error && (
            <div className="text-sm text-[color:var(--color-down)]">{previewSave.error}</div>
          )}
          <div className="flex gap-2">
            <button type="button" className={btnPrimary} disabled={previewSave.saving} onClick={runPreview}>
              预检
            </button>
            <button type="button" className={btnGhost} onClick={() => setFlow(null)}>取消</button>
          </div>
          {flow.items && <ChecklistConfirmCard items={flow.items} onSubmit={submitFromCard} />}
        </div>
      )}
    </section>
  );
}
