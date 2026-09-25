"use client";

import { useState } from "react";
import type { FundScreeningParams } from "@/lib/types";

/** 类别六桶（与后端 etf_basic.category 白名单一致；「其他」当前 0 行属正常）。 */
export const CATEGORY_OPTIONS = ["宽基", "行业", "商品", "债券", "QDII", "其他"] as const;

/** TE 口径提示：表单控件与结果表头共用同一文案（T16 口径：收盘价自算，含分红/折溢价噪声）。 */
export const TRACKING_ERROR_NOTE =
  "跟踪误差为收盘价口径（含分红/折溢价噪声），与官方净值口径不可直接对比";

/** 三数值条件，label 即单位口径说明（feeRate=年化%、scale=亿元、TE=小数）。 */
const FIELDS: { key: keyof FundScreeningParams; label: string; placeholder: string }[] = [
  { key: "feeRateMax", label: "费率 < %（年化，0.6=0.6%）", placeholder: "如 0.6" },
  { key: "scaleMin", label: "规模 > 亿元", placeholder: "如 10" },
  { key: "trackingErrorMax", label: "跟踪误差 <（小数，0.05=5%）", placeholder: "如 0.05" },
];

export default function FundScreeningForm({ params, onChange, onSubmit, loading }: {
  params: FundScreeningParams;
  onChange: (key: keyof FundScreeningParams, value: string) => void;
  onSubmit: () => void;
  loading: boolean;
}) {
  const [emptyHint, setEmptyHint] = useState(false);
  const value = (k: keyof FundScreeningParams) => params[k] ?? "";
  // 至少一条件预检（与后端 NO_CONDITION 对齐）：四维中任一非空即可提交
  const hasCondition = [params.feeRateMax, params.scaleMin, params.trackingErrorMax, params.category]
    .some((v) => v !== undefined && v !== null && v !== "");
  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        if (!hasCondition) { setEmptyHint(true); return; }
        setEmptyHint(false);
        onSubmit();
      }}
      className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-4"
    >
      <fieldset className="space-y-2">
        <legend className="text-sm font-medium text-[color:var(--color-ink-dim)]">四维筛选</legend>
        <div className="grid md:grid-cols-3 gap-4">
          {FIELDS.map((f) => (
            <div key={f.key} className="space-y-1">
              <label className="flex items-center justify-between gap-2 text-sm">
                <span className="text-[color:var(--color-ink-dim)]">{f.label}</span>
                <input
                  type="number"
                  step="any"
                  title={f.key === "trackingErrorMax" ? TRACKING_ERROR_NOTE : undefined}
                  className="w-28 rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-2 py-1 tabular"
                  value={value(f.key)}
                  placeholder={f.placeholder}
                  onChange={(e) => onChange(f.key, e.target.value)}
                />
              </label>
              {f.key === "trackingErrorMax" && (
                <div className="text-xs text-[color:var(--color-ink-faint)]">{TRACKING_ERROR_NOTE}</div>
              )}
            </div>
          ))}
        </div>
      </fieldset>
      <div className="flex items-center gap-3">
        <label className="flex items-center gap-2 text-sm">
          <span className="text-[color:var(--color-ink-dim)]">类别</span>
          <select
            className="rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-2 py-1"
            aria-label="类别"
            value={params.category ?? ""}
            onChange={(e) => onChange("category", e.target.value)}
          >
            <option value="">全部</option>
            {CATEGORY_OPTIONS.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
        </label>
        <button type="submit" disabled={loading} className="rounded-lg bg-[color:var(--color-up)] px-4 py-1.5 text-sm text-white disabled:opacity-50">
          {loading ? "筛选中…" : "筛选"}
        </button>
        {emptyHint && <span className="text-sm text-[color:var(--color-up)]">请至少填写一个筛选条件</span>}
      </div>
    </form>
  );
}
