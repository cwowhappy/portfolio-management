"use client";
// 公告流 Panel：过滤区（标的/类型下拉/仅重大）+ 列表 + 服务端分页。
// 类型徽标链与业绩要点行照 chat 工具卡 ANN 先例（toolRenderers）：annTypes 中文 →
// annTypeSource → 「其他」；metrics 六字段非空才出（未披露为 null，不编造，F07 契约）。

import { useCallback, useEffect, useRef, useState } from "react";
import {
  fetchAnnouncements,
  type AnnouncementItem,
  type AnnouncementPage,
  type AnnouncementType,
} from "@/lib/intelligenceApi";
import {
  IntelEmptyFiltered,
  IntelEmptyGuide,
  LabeledCheckbox,
  LabeledInput,
  LabeledSelect,
  formatPublished,
} from "./IntelligenceFilters";
import PaginationBar from "./PaginationBar";

// 枚举→中文（T3 审查裁定：组件层 Record<枚举,string>，编译期漏键即错；11 值全量）
const ANNOUNCEMENT_TYPE_LABELS: Record<AnnouncementType, string> = {
  INCREASE_HOLD: "股东增持",
  DECREASE_HOLD: "股东减持",
  BUYBACK: "股份回购",
  PLACEMENT: "定增配股",
  RELATED_TRANSACTION: "关联交易",
  EARNINGS_FORECAST: "业绩预告",
  EARNINGS_FLASH: "业绩快报",
  PERIODIC_REPORT: "定期报告",
  EQUITY_INCENTIVE: "股权激励",
  DELISTING_RISK: "退市风险",
  OTHER: "其他",
};
const TYPE_OPTIONS: Array<{ value: AnnouncementType | ""; label: string }> = [
  { value: "", label: "全部类型" },
  ...(Object.keys(ANNOUNCEMENT_TYPE_LABELS) as AnnouncementType[]).map((t) => ({
    value: t,
    label: ANNOUNCEMENT_TYPE_LABELS[t],
  })),
];

const PAGE_SIZE = 20;

/** 类型徽标文案：annTypes 中文（剔 OTHER）→ annTypeSource → 「其他」（与推送卡片同链）。 */
function typeBadgeText(it: AnnouncementItem): string {
  const joined = it.annTypes
    .filter((t) => t !== "OTHER")
    .map((t) => ANNOUNCEMENT_TYPE_LABELS[t])
    .join("、");
  if (joined) return joined;
  return it.annTypeSource && it.annTypeSource.trim() !== ""
    ? it.annTypeSource
    : ANNOUNCEMENT_TYPE_LABELS.OTHER;
}

/** 六字段要点行（非空字段才出；同比/毛利率带符号；全空返回空串不渲染）。 */
function metricsLine(it: AnnouncementItem): string {
  const m = it.metrics;
  if (!m) return "";
  const pct = (v: number) => (v >= 0 ? "+" : "") + v + "%";
  const parts: string[] = [];
  if (m.revenueYi != null) parts.push(`营收 ${m.revenueYi} 亿`);
  if (m.netProfitYi != null) parts.push(`归母净利 ${m.netProfitYi} 亿`);
  if (m.netProfitYoyPct != null) parts.push(`净利同比 ${pct(m.netProfitYoyPct)}`);
  if (m.deductedProfitYi != null) parts.push(`扣非 ${m.deductedProfitYi} 亿`);
  if (m.grossMarginPct != null) parts.push(`毛利率 ${pct(m.grossMarginPct)}`);
  if (m.dividendDesc) parts.push(`分红 ${m.dividendDesc}`);
  return parts.join(" · ");
}

export default function AnnouncementFeedPanel({
  q,
  initialStock = "",
}: {
  q: string;
  initialStock?: string;
}) {
  const [stock, setStock] = useState(initialStock);
  const [type, setType] = useState<AnnouncementType | "">("");
  const [major, setMajor] = useState(false);
  const [page, setPage] = useState(1);
  const [data, setData] = useState<AnnouncementPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    fetchAnnouncements({
      stock: stock || undefined,
      type: type || undefined,
      q: q || undefined,
      major: major || undefined,
      page,
      pageSize: PAGE_SIZE,
    })
      .then((d) => {
        if (seq !== requestSeqRef.current) return;
        setData(d);
        setError(null);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载失败");
      })
      .finally(() => {
        if (seq === requestSeqRef.current) setLoading(false);
      });
  }, [q, stock, type, major, page]);

  useEffect(() => {
    reload();
  }, [reload]);

  if (error) {
    return (
      <div
        className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-6 text-sm text-[color:var(--color-ink-dim)] space-y-3"
        data-testid="intel-error"
      >
        <div>加载失败：{error}</div>
        <button
          type="button"
          className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-xs text-[color:var(--color-ink-dim)] hover:border-[color:var(--color-ink-faint)]"
          onClick={() => {
            setError(null);
            reload();
          }}
        >
          重试
        </button>
      </div>
    );
  }

  const hasFilter = !!(q || stock || type || major);

  return (
    <div className="space-y-4" data-testid="intel-announcements-panel">
      <div className="flex flex-wrap items-center gap-3">
        <LabeledInput
          label="公告标的"
          inputProps={{
            value: stock,
            placeholder: "如 600519",
            onChange: (e) => {
              setStock(e.target.value);
              setPage(1);
            },
          }}
        />
        <LabeledSelect
          label="公告类型"
          value={type}
          options={TYPE_OPTIONS}
          onChange={(v) => {
            setType(v);
            setPage(1);
          }}
        />
        <LabeledCheckbox
          label="仅重大公告"
          checked={major}
          onChange={(v) => {
            setMajor(v);
            setPage(1);
          }}
        />
      </div>

      {loading ? (
        <div className="h-40 rounded-2xl skeleton" aria-label="加载中" />
      ) : data == null ? null : data.items.length === 0 ? (
        hasFilter ? (
          <IntelEmptyFiltered
            onClear={() => {
              setStock("");
              setType("");
              setMajor(false);
              setPage(1);
            }}
          />
        ) : (
          <IntelEmptyGuide />
        )
      ) : (
        <>
          <ul
            className="divide-y divide-[color:var(--color-line-soft)] rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 px-4"
            data-testid="intel-announcements-list"
          >
            {data.items.map((it) => {
              const time = formatPublished(it.publishedAt);
              const line = metricsLine(it);
              return (
                <li key={it.pdfUrl} className="py-3">
                  <div className="flex items-start gap-2">
                    <a
                      href={it.pdfUrl}
                      target="_blank"
                      rel="noreferrer"
                      className="min-w-0 flex-1 truncate text-sm text-[color:var(--color-accent)] hover:underline"
                    >
                      {it.title}
                    </a>
                    <span className="shrink-0 rounded border border-[color:var(--color-line)] px-1.5 py-0.5 text-[10px] leading-none text-[color:var(--color-ink-dim)]">
                      {typeBadgeText(it)}
                    </span>
                    {time && (
                      <span className="shrink-0 text-[11px] tabular text-[color:var(--color-ink-faint)]">
                        {time}
                      </span>
                    )}
                  </div>
                  <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-[color:var(--color-ink-faint)]">
                    <span>
                      {it.stockName} {it.stockCode}
                    </span>
                    {line && <span className="tabular">{line}</span>}
                  </div>
                </li>
              );
            })}
          </ul>
          <PaginationBar
            page={data.page}
            pageSize={data.pageSize}
            total={data.total}
            onPageChange={setPage}
          />
        </>
      )}
    </div>
  );
}
