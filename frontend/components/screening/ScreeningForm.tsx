"use client";

import type { ScreeningParams, IndustryValuation } from "@/lib/types";

const GROUPS: { title: string; fields: { key: keyof ScreeningParams; label: string; placeholder: string }[] }[] = [
  { title: "估值水平", fields: [
    { key: "peTtmMax", label: "PE-TTM <", placeholder: "如 20" },
    { key: "pbMax", label: "PB <", placeholder: "如 2" },
    { key: "dividendYieldMin", label: "股息率 > %", placeholder: "如 3" },
  ]},
  { title: "盈利能力", fields: [
    { key: "roeMin", label: "ROE > %", placeholder: "如 15" },
    { key: "roaMin", label: "ROA > %", placeholder: "如 8" },
    { key: "grossMarginMin", label: "毛利率 > %", placeholder: "如 30" },
  ]},
  { title: "财务健康", fields: [
    { key: "debtToAssetsMax", label: "资产负债率 < %", placeholder: "如 60" },
    { key: "currentRatioMin", label: "流动比率 >", placeholder: "如 1.5" },
  ]},
  { title: "成长与稳定", fields: [
    { key: "revenueYoyMin", label: "营收增速 > %", placeholder: "如 10" },
    { key: "netprofitYoyMin", label: "净利增速 > %", placeholder: "如 10" },
  ]},
  { title: "市值与流动性", fields: [
    { key: "totalMvMin", label: "总市值 > 亿", placeholder: "如 100" },
    { key: "turnoverRateMin", label: "换手率 > %", placeholder: "如 1" },
  ]},
];

export default function ScreeningForm({ params, industries, onChange, onSubmit, loading }: {
  params: ScreeningParams;
  industries: IndustryValuation[];
  onChange: (key: keyof ScreeningParams, value: string) => void;
  onSubmit: () => void;
  loading: boolean;
}) {
  const value = (k: keyof ScreeningParams) => params[k] ?? "";
  return (
    <form onSubmit={(e) => { e.preventDefault(); onSubmit(); }} className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-4">
      <div className="grid md:grid-cols-3 gap-4">
        {GROUPS.map((g) => (
          <fieldset key={g.title} className="space-y-2">
            <legend className="text-sm font-medium text-[color:var(--color-ink-dim)]">{g.title}</legend>
            {g.fields.map((f) => (
              <label key={f.key} className="flex items-center justify-between gap-2 text-sm">
                <span className="text-[color:var(--color-ink-dim)]">{f.label}</span>
                <input
                  type="number"
                  step="any"
                  className="w-28 rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-2 py-1 tabular"
                  value={value(f.key)}
                  placeholder={f.placeholder}
                  onChange={(e) => onChange(f.key, e.target.value)}
                />
              </label>
            ))}
          </fieldset>
        ))}
      </div>
      <div className="flex items-center gap-3">
        <label className="flex items-center gap-2 text-sm">
          <span className="text-[color:var(--color-ink-dim)]">行业</span>
          <select
            className="rounded-lg border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-2 py-1"
            value={params.industryCode ?? ""}
            onChange={(e) => onChange("industryCode", e.target.value)}
          >
            <option value="">全部</option>
            {industries.map((i) => (
              <option key={i.industryCode} value={i.industryCode}>{i.industryName}</option>
            ))}
          </select>
        </label>
        <button type="submit" disabled={loading} className="rounded-lg bg-[color:var(--color-up)] px-4 py-1.5 text-sm text-white disabled:opacity-50">
          {loading ? "筛选中…" : "筛选"}
        </button>
      </div>
    </form>
  );
}
