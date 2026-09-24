"use client";

import { useMemo } from "react";
import { EChart } from "@/components/charts/EChart";
import { buildBarOption } from "@/components/charts/optionBuilders";
import type { Attribution } from "@/lib/types";

// 后端数值一律 toPlainString 字符串（如 "0.0015000000"）：parse 后 ×100 转百分点再格式化。
function pct(s: string): string {
  return `${(Number(s) * 100).toFixed(2)}%`;
}

/** 归因区（MS-13 F09）：行业×（配置/选择）双柱图 + 现金/总超额/残差汇总。 */
export default function AttributionSection({ attribution }: { attribution: Attribution }) {
  // 贡献绝对值降序：|配置|+|选择| 大的行业靠前（稳定阅读顺序）
  const sorted = useMemo(
    () =>
      [...attribution.rows].sort((a, b) =>
        Math.abs(Number(b.allocation)) + Math.abs(Number(b.selection))
        - Math.abs(Number(a.allocation)) - Math.abs(Number(a.selection))),
    [attribution.rows],
  );
  const option = useMemo(
    () =>
      buildBarOption({
        specVersion: 1, type: "bar",
        title: "行业贡献（对沪深300）",
        subtitle: `${attribution.windowStart ?? "—"} ~ ${attribution.windowEnd ?? "—"} · 日超额算术累计口径`,
        categories: sorted.map((r) => r.industryName ?? r.industry),
        series: [
          { name: "配置贡献", data: sorted.map((r) => Number(r.allocation) * 100) },
          { name: "选择贡献", data: sorted.map((r) => Number(r.selection) * 100) },
        ],
        unit: "%",
      }),
    [sorted, attribution.windowStart, attribution.windowEnd],
  );
  return (
    <div data-testid="attribution-section" className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-1">行业贡献（对沪深300）</div>
      <div className="text-xs text-[color:var(--color-ink-faint)] mb-3">
        {attribution.windowStart ?? "—"} ~ {attribution.windowEnd ?? "—"} · 日超额算术累计口径
      </div>
      <EChart option={option} height={360} testid="attribution-chart" />
      <div className="mt-2 flex flex-wrap gap-4 text-sm text-[color:var(--color-ink-dim)]">
        <span>总超额 <span className="tabular text-[color:var(--color-ink)]">{pct(attribution.totalExcess)}</span></span>
        <span>现金贡献 <span className="tabular text-[color:var(--color-ink)]">{pct(attribution.cashAllocation)}</span></span>
        <span>残差 <span className="tabular">{pct(attribution.residual)}</span></span>
        {Number(attribution.unmappedValueShare) > 0 && (
          <span>未映射行业持仓占比 {pct(attribution.unmappedValueShare)}</span>
        )}
      </div>
    </div>
  );
}
