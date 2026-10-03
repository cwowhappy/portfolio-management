"use client";
// 简报归档 Panel：左日期列表（PageView + 日期范围过滤 + 分页）+ 右详情。
// contentMd 经共享 MarkdownView 渲染（react-markdown + remark-gfm 已有先例，不新增依赖）；
// 缺档 404 由 lib 层 reject(message)，这里按未存档空态呈现。详情请求独立 seq 守卫（快速换档丢弃过期）。

import { useCallback, useEffect, useRef, useState } from "react";
import { fetchBriefDetail, fetchBriefs, type BriefDetail, type BriefPage, type BriefStatus } from "@/lib/intelligenceApi";
import MarkdownView from "@/components/shared/MarkdownView";
import {
  IntelEmptyFiltered,
  IntelEmptyGuide,
  LabeledInput,
  formatPublished,
} from "./IntelligenceFilters";
import PaginationBar from "./PaginationBar";

// 枚举→中文（T3 审查裁定：组件层 Record<枚举,string>，编译期漏键即错）
const BRIEF_STATUS_LABELS: Record<BriefStatus, string> = {
  GENERATED: "已生成",
  EMPTY_SIMPLE: "简式",
  FAILED: "失败",
};

const PAGE_SIZE = 20;

export default function BriefArchivePanel({ q }: { q: string }) {
  const [from, setFrom] = useState("");
  const [to, setTo] = useState("");
  const [page, setPage] = useState(1);
  const [data, setData] = useState<BriefPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);

  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<BriefDetail | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const detailSeqRef = useRef(0);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    fetchBriefs({
      from: from || undefined,
      to: to || undefined,
      q: q || undefined,
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
  }, [q, from, to, page]);

  useEffect(() => {
    reload();
  }, [reload]);

  const openDetail = useCallback((tradeDate: string) => {
    setSelected(tradeDate);
    const seq = ++detailSeqRef.current;
    setDetailLoading(true);
    setDetailError(null);
    fetchBriefDetail(tradeDate)
      .then((d) => {
        if (seq !== detailSeqRef.current) return;
        setDetail(d);
      })
      .catch((e) => {
        if (seq !== detailSeqRef.current) return;
        setDetail(null);
        setDetailError(e instanceof Error ? e.message : "加载失败");
      })
      .finally(() => {
        if (seq === detailSeqRef.current) setDetailLoading(false);
      });
  }, []);

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

  const hasFilter = !!(q || from || to);

  return (
    <div className="grid items-start gap-6 md:grid-cols-[300px_1fr]" data-testid="intel-briefs-panel">
      <div className="space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <LabeledInput
            label="开始日期"
            inputProps={{
              type: "date",
              value: from,
              onChange: (e) => {
                setFrom(e.target.value);
                setPage(1);
              },
            }}
          />
          <LabeledInput
            label="结束日期"
            inputProps={{
              type: "date",
              value: to,
              onChange: (e) => {
                setTo(e.target.value);
                setPage(1);
              },
            }}
          />
        </div>

        {loading ? (
          <div className="h-40 rounded-2xl skeleton" aria-label="加载中" />
        ) : data == null ? null : data.items.length === 0 ? (
          hasFilter ? (
            <IntelEmptyFiltered
              onClear={() => {
                setFrom("");
                setTo("");
                setPage(1);
              }}
            />
          ) : (
            <IntelEmptyGuide />
          )
        ) : (
          <>
            <ul
              className="divide-y divide-[color:var(--color-line-soft)] rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70"
              data-testid="intel-briefs-list"
            >
              {data.items.map((it) => (
                <li key={it.tradeDate}>
                  <button
                    type="button"
                    aria-label={`查看 ${it.tradeDate} 简报`}
                    className={
                      "flex w-full flex-wrap items-center gap-x-3 gap-y-0.5 px-4 py-2.5 text-left text-sm " +
                      (selected === it.tradeDate
                        ? "bg-[color:var(--color-panel)] text-[color:var(--color-ink)]"
                        : "text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)]/60")
                    }
                    onClick={() => openDetail(it.tradeDate)}
                  >
                    <span className="tabular">{it.tradeDate}</span>
                    <span className="text-[11px] text-[color:var(--color-ink-faint)]">
                      {BRIEF_STATUS_LABELS[it.status]}
                    </span>
                    {it.topStocks.length > 0 && (
                      <span className="text-[11px] tabular text-[color:var(--color-ink-faint)]">
                        {it.topStocks.join(" / ")}
                      </span>
                    )}
                  </button>
                </li>
              ))}
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

      <div className="min-h-64 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
        {!selected ? (
          <div
            className="grid h-40 place-items-center text-sm text-[color:var(--color-ink-faint)]"
            data-testid="brief-detail-hint"
          >
            选择左侧交易日查看当日简报
          </div>
        ) : detailLoading ? (
          <div className="h-40 rounded-xl skeleton" aria-label="加载中" />
        ) : detailError ? (
          <div
            className="grid h-40 place-items-center space-y-2 text-center text-sm text-[color:var(--color-ink-faint)]"
            data-testid="brief-detail-empty"
          >
            <div>{detailError}</div>
            <div className="text-xs">该交易日无简报档——可从数据台生成后回看。</div>
          </div>
        ) : detail ? (
          <div className="space-y-3" data-testid="brief-detail">
            <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-[color:var(--color-ink-faint)]">
              <span className="tabular text-sm text-[color:var(--color-ink)]">{detail.tradeDate}</span>
              <span>{BRIEF_STATUS_LABELS[detail.status]}</span>
              <span>模型 {detail.model}</span>
              <span className="tabular">生成 {formatPublished(detail.generatedAt)}</span>
              {detail.topStocks.length > 0 && (
                <span className="tabular">{detail.topStocks.join(" / ")}</span>
              )}
            </div>
            {detail.status === "FAILED" && detail.failReason && (
              <div className="rounded-md border border-[color:var(--color-line)] px-3 py-2 text-xs text-[color:var(--color-ink-faint)]">
                失败原因：{detail.failReason}
              </div>
            )}
            <MarkdownView content={detail.contentMd} />
          </div>
        ) : null}
      </div>
    </div>
  );
}
