"use client";
// 新闻流 Panel：过滤区（标的/行业/重要度下限）+ 列表 + 服务端分页。
// 方向徽标色照 chat 工具卡 ANN 先例（toolRenderers）：BULLISH=利好绿 / BEARISH=利空红 /
// NEUTRAL=中性灰——情报语义配色与 A股红涨绿跌行情色相反，复用 --color-down/up 色值随主题联动。

import { useCallback, useEffect, useRef, useState } from "react";
import { fetchNews, type NewsDirection, type NewsPage } from "@/lib/intelligenceApi";
import {
  IntelEmptyFiltered,
  IntelEmptyGuide,
  LabeledInput,
  LabeledSelect,
  formatPublished,
} from "./IntelligenceFilters";
import PaginationBar from "./PaginationBar";

// 枚举→中文（T3 审查裁定：label 放组件层 Record<枚举,string>，编译期漏键即错）
const DIRECTION_LABELS: Record<NewsDirection, string> = {
  BULLISH: "利好",
  BEARISH: "利空",
  NEUTRAL: "中性",
};
const DIRECTION_CLASS: Record<NewsDirection, string> = {
  BULLISH: "border-[color:var(--color-down)] text-[color:var(--color-down)]",
  BEARISH: "border-[color:var(--color-up)] text-[color:var(--color-up)]",
  NEUTRAL: "border-[color:var(--color-line)] text-[color:var(--color-ink-dim)]",
};
// 重要度域为 0..100（NewsFilter 契约），下拉取三档常用下限
const IMPORTANCE_OPTIONS = [
  { value: "", label: "全部重要度" },
  { value: "20", label: "重要度 ≥20" },
  { value: "50", label: "重要度 ≥50" },
  { value: "80", label: "重要度 ≥80" },
];

const PAGE_SIZE = 20;

export default function NewsFeedPanel({
  q,
  initialStock = "",
  initialIndustry = "",
}: {
  q: string;
  initialStock?: string;
  initialIndustry?: string;
}) {
  const [stock, setStock] = useState(initialStock);
  const [industry, setIndustry] = useState(initialIndustry);
  const [minImportance, setMinImportance] = useState("");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<NewsPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);

  // seq 守卫照 research/JournalBoard：loading 只用于首屏骨架，过滤变更原地刷新、丢弃过期响应
  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    fetchNews({
      q: q || undefined,
      stock: stock || undefined,
      industry: industry || undefined,
      minImportance: minImportance ? Number(minImportance) : undefined,
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
  }, [q, stock, industry, minImportance, page]);

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

  const hasFilter = !!(q || stock || industry || minImportance);

  return (
    <div className="space-y-4" data-testid="intel-news-panel">
      <div className="flex flex-wrap items-center gap-3">
        <LabeledInput
          label="新闻标的"
          inputProps={{
            value: stock,
            placeholder: "如 600519",
            onChange: (e) => {
              setStock(e.target.value);
              setPage(1);
            },
          }}
        />
        <LabeledInput
          label="新闻行业"
          inputProps={{
            value: industry,
            placeholder: "如 BK0477",
            onChange: (e) => {
              setIndustry(e.target.value);
              setPage(1);
            },
          }}
        />
        <LabeledSelect
          label="新闻重要度"
          value={minImportance}
          options={IMPORTANCE_OPTIONS}
          onChange={(v) => {
            setMinImportance(v);
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
              setIndustry("");
              setMinImportance("");
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
            data-testid="intel-news-list"
          >
            {data.items.map((it, i) => {
              const time = formatPublished(it.publishedAt);
              const codes = it.stockCodes.join(" / ");
              return (
                <li key={`${i}-${it.url ?? it.title ?? ""}`} className="py-3">
                  <div className="flex items-start gap-2">
                    <a
                      href={it.url ?? undefined}
                      target="_blank"
                      rel="noreferrer"
                      className="min-w-0 flex-1 truncate text-sm text-[color:var(--color-accent)] hover:underline"
                    >
                      {it.title}
                    </a>
                    {it.direction && (
                      <span
                        className={
                          "shrink-0 rounded border px-1.5 py-0.5 text-[10px] leading-none " +
                          DIRECTION_CLASS[it.direction]
                        }
                      >
                        {DIRECTION_LABELS[it.direction]}
                      </span>
                    )}
                    {it.importance != null && (
                      <span className="shrink-0 text-[11px] tabular text-[color:var(--color-ink-faint)]">
                        重要度 {it.importance}
                      </span>
                    )}
                  </div>
                  {it.summary && (
                    <p className="mt-1 line-clamp-2 text-xs leading-relaxed text-[color:var(--color-ink-dim)]">
                      {it.summary}
                    </p>
                  )}
                  {(time || codes) && (
                    <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-[color:var(--color-ink-faint)]">
                      {time && <span className="tabular">{time}</span>}
                      {codes && <span className="tabular">{codes}</span>}
                    </div>
                  )}
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
