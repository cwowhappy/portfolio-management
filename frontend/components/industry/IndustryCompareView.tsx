"use client";

import { useMemo, useState } from "react";
import type { IndustryBoardItem, Prosperity } from "@/lib/types";
import type { AuthUser } from "@/lib/auth";
import { EChart } from "@/components/charts/EChart";
import { buildBarOption } from "@/components/charts/optionBuilders";
import ResearchNoteDialog from "@/components/wiki/ResearchNoteDialog";

const PROSPERITY_LABEL: Record<Prosperity, string> = { UP: "↑", FLAT: "→", DOWN: "↓" };

/** 分位口径提示（照 IndustryBoardTable 先例，需求 §三.A）。 */
const PERCENTILE_NOTE = "当前值在近 5 年序列经验分布中的百分位（高=贵）；历史按当前申万 2021 分类成分回溯重算";

/** 指标切换（五选一，默认 PE；ROE/景气不进图，只在并排表呈现）。 */
type MetricKey = "pe" | "pb" | "dividendYield" | "pePercentile" | "pbPercentile";
const METRICS: [MetricKey, string][] = [
  ["pe", "PE"], ["pb", "PB"], ["dividendYield", "股息率"],
  ["pePercentile", "PE分位"], ["pbPercentile", "PB分位"],
];

/** 行业对比视图（/industry「对比」页签）：多选（关注集默认勾选）→ 并排对比表 + 指标柱状图 + 研究笔记入口。 */
export default function IndustryCompareView({ items, watchedCodes, onToggleWatch, user }: {
  items: IndustryBoardItem[];
  watchedCodes: Set<string>;
  onToggleWatch: (industryCode: string) => void;
  user: AuthUser | null;
}) {
  const [query, setQuery] = useState("");
  const [metric, setMetric] = useState<MetricKey>("pe");
  // 关注集默认勾选：用户手动改动前跟随 watchedCodes 派生（items 先到、关注集后到的竞态也能回填默认勾选）
  const [touched, setTouched] = useState(false);
  const [manual, setManual] = useState<Set<string>>(new Set());
  const watchDefault = useMemo(
    () => new Set(items.filter((i) => watchedCodes.has(i.industryCode)).map((i) => i.industryCode)),
    [items, watchedCodes],
  );
  const selected = touched ? manual : watchDefault;

  const filtered = items.filter((i) => i.industryName.includes(query.trim()));
  // 选中行保持 items 序（「首个勾选行业」= 选中集中 items 序最前者，笔记归属口径 §三B）
  const selectedItems = items.filter((i) => selected.has(i.industryCode));
  const canCompare = selectedItems.length >= 2;
  const first = selectedItems[0];

  const toggleSelected = (industryCode: string) => {
    setTouched(true);
    const next = new Set(selected);
    if (next.has(industryCode)) next.delete(industryCode);
    else next.add(industryCode);
    setManual(next);
  };

  const metricLabel = METRICS.find(([k]) => k === metric)![1];
  const chartOption = buildBarOption({
    specVersion: 1,
    type: "bar",
    title: `行业${metricLabel}对比`,
    categories: selectedItems.map((i) => i.industryName),
    series: [{ name: metricLabel, data: selectedItems.map((i) => i[metric]) }],
    unit: metric === "dividendYield" || metric.endsWith("Percentile") ? "%" : undefined,
  });

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div className="font-[family-name:var(--font-display)] text-[15px]">行业对比</div>
        {user && (
          first ? (
            <div className="flex items-center gap-2">
              <span className="text-xs text-[color:var(--color-ink-faint)]">笔记归属：{first.industryName}</span>
              <ResearchNoteDialog industryCode={first.industryCode} industryName={first.industryName} />
            </div>
          ) : (
            <span className="text-xs text-[color:var(--color-ink-faint)]">勾选行业后可保存研究结论</span>
          )
        )}
      </div>

      {/* 行业多选列表：checkbox + 行业名 + ⭐（关注回调复用，未登录跳登录由父层处理） */}
      <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="industry-compare-picker">
        <input
          aria-label="搜索行业"
          placeholder="搜索行业"
          className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <div className="mt-3 max-h-72 divide-y divide-[color:var(--color-line-soft)] overflow-y-auto">
          {filtered.map((i) => (
            <div key={i.industryCode} className="flex items-center gap-2 py-1.5">
              <label className="flex flex-1 cursor-pointer items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={selected.has(i.industryCode)}
                  onChange={() => toggleSelected(i.industryCode)}
                />
                {i.industryName}
              </label>
              <button
                type="button"
                className="text-base leading-none"
                aria-label={watchedCodes.has(i.industryCode) ? `取消关注 ${i.industryCode}` : `关注 ${i.industryCode}`}
                onClick={() => onToggleWatch(i.industryCode)}
              >
                {watchedCodes.has(i.industryCode) ? "★" : "☆"}
              </button>
            </div>
          ))}
        </div>
      </div>

      {canCompare ? (
        <>
          {/* 并排对比表：行=选中行业、列=七指标+景气（照 IndustryBoardTable 取值/口径先例） */}
          <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5" data-testid="industry-compare-table">
            <table className="w-full text-sm">
              <thead className="text-[color:var(--color-ink-dim)]">
                <tr>
                  <th className="text-left py-1">行业</th>
                  <th className="text-right py-1">PE</th>
                  <th className="text-right py-1">PB</th>
                  <th className="text-right py-1">ROE</th>
                  <th className="text-right py-1">股息率</th>
                  <th className="text-right py-1" title={PERCENTILE_NOTE}>PE 5y分位</th>
                  <th className="text-right py-1" title={PERCENTILE_NOTE}>PB 5y分位</th>
                  <th className="text-right py-1">景气</th>
                </tr>
              </thead>
              <tbody className="tabular">
                {selectedItems.map((i) => (
                  <tr key={i.industryCode} className="border-t border-[color:var(--color-line-soft)]">
                    <td className="text-left py-2">{i.industryName}</td>
                    <td className="text-right">{i.pe ?? "—"}</td>
                    <td className="text-right">{i.pb ?? "—"}</td>
                    <td className="text-right">{i.roe ?? "—"}</td>
                    <td className="text-right">{i.dividendYield ?? "—"}</td>
                    <td className="text-right">{fmtPct(i.pePercentile)}</td>
                    <td className="text-right">{fmtPct(i.pbPercentile)}</td>
                    <td className="text-right">
                      {i.prosperity ? (
                        <span title={prosperityTitle(i)}>{PROSPERITY_LABEL[i.prosperity]}</span>
                      ) : "—"}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* 对比图：x 轴=选中行业名，单系列=当前指标值（指标按钮组五选一） */}
          <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
            <div className="mb-3 flex flex-wrap gap-2 text-sm">
              {METRICS.map(([k, label]) => (
                <button
                  key={k}
                  type="button"
                  aria-pressed={metric === k}
                  className={`rounded-md px-3 py-1.5 ${metric === k
                    ? "bg-[color:var(--color-panel)] text-[color:var(--color-ink)]"
                    : "text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)]/60"}`}
                  onClick={() => setMetric(k)}
                >
                  {label}
                </button>
              ))}
            </div>
            <EChart option={chartOption} height={280} testid="industry-compare-chart" />
          </div>
        </>
      ) : (
        <div className="rounded-2xl border border-dashed border-[color:var(--color-line)] p-6 text-center text-sm text-[color:var(--color-ink-dim)]">
          勾选至少两个行业开始对比
        </div>
      )}
    </div>
  );
}

// 取值格式化与景气口径（与 IndustryBoardTable 同式；T10 文件勿改，就地复刻）
function fmtPct(v: number | null): string {
  return v == null ? "—" : `${v.toFixed(1)}%`;
}

function prosperityTitle(i: IndustryBoardItem): string {
  const p = i.prosperityInputs;
  if (!p) return "";
  return `ROEΔ中位数 ${p.roeDeltaMedian ?? "—"}pp · 营收增速中位数 ${p.revenueYoyMedian ?? "—"}% · 样本 ${p.sampleSize}（近4季均值 vs 前4季；上行=Δ≥+1且增速≥+10）`;
}
