"use client";

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  useTable,
  tableFeatures,
  rowSortingFeature,
  createSortedRowModel,
  sortFn_alphanumeric,
  flexRender,
  columnVisibilityFeature,
  createColumnHelper,
} from "@tanstack/react-table";
import { adminApi, type TraceItem, type TracePage } from "@/lib/adminApi";
import { formatInstant, preview } from "./format";

const PAGE_SIZE = 20;

const ghostBtn =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-3 py-1.5 text-[12px] text-[color:var(--color-ink-dim)] transition-all enabled:hover:border-[color:var(--color-line)] enabled:hover:text-[color:var(--color-ink)] disabled:cursor-not-allowed disabled:opacity-40";

const inputCls =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg)] px-2.5 py-1.5 text-[12px] text-[color:var(--color-ink)] focus:outline-none focus:border-[color:var(--color-up)]";

// v9 特性装配沿 DataTable.tsx 先例（getVisibleCells 挂 columnVisibilityFeature、
// 排序注册表只装用到的）；本表为本地列定义——数据非后端 TableSpec 通道（设计规格 §7.2）。
const FEATURES = tableFeatures({
  rowSortingFeature,
  columnVisibilityFeature,
  sortedRowModel: createSortedRowModel(),
  sortFns: { alphanumeric: sortFn_alphanumeric },
});

const helper = createColumnHelper<typeof FEATURES, TraceItem>();

/**
 * 观测区块①：工具调用明细（tool_invocation_obs 倒序分页 + 筛选）。
 * from/to 日期按 UTC 零点折算 ISO 时刻（admin 诊断口径）；筛选草稿经「查询」应用。
 */
export default function TraceTable() {
  // 筛选草稿（输入中态，「查询」提交才生效）
  const [toolDraft, setToolDraft] = useState("");
  const [failedDraft, setFailedDraft] = useState<"" | "true" | "false">("");
  const [fromDraft, setFromDraft] = useState("");
  const [toDraft, setToDraft] = useState("");
  // 已应用筛选 + 分页
  const [applied, setApplied] = useState<{ tool: string; failed: "" | "true" | "false"; from: string; to: string }>({
    tool: "",
    failed: "",
    from: "",
    to: "",
  });
  const [page, setPage] = useState(0);
  const [data, setData] = useState<TracePage | null>(null);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0); // 竞态守卫（沿 AnalyticsBoard.tsx:52-58）

  const load = useCallback(
    (targetPage: number, filter: typeof applied) => {
      const seq = ++requestSeqRef.current;
      adminApi
        .fetchTrace({
          page: targetPage,
          size: PAGE_SIZE,
          ...(filter.tool ? { tool: filter.tool } : {}),
          ...(filter.failed ? { failed: filter.failed === "true" } : {}),
          ...(filter.from ? { from: `${filter.from}T00:00:00Z` } : {}),
          ...(filter.to ? { to: `${filter.to}T00:00:00Z` } : {}),
        })
        .then((p) => {
          if (seq !== requestSeqRef.current) return; // 已有更新的请求，丢弃过期响应
          setData(p);
          setError(null);
        })
        .catch((e) => {
          if (seq !== requestSeqRef.current) return;
          setError(e instanceof Error ? e.message : "加载工具调用明细失败");
        });
    },
    [],
  );

  useEffect(() => {
    load(page, applied);
  }, [load, page, applied]);

  function applyFilters() {
    setPage(0); // 筛选变更回到第 0 页（page 已为 0 时靠 applied 身份变化触发重取）
    setApplied({
      tool: toolDraft.trim(),
      failed: failedDraft,
      from: fromDraft,
      to: toDraft,
    });
  }

  // helper.columns 包装保留各列 TValue（异构 cell 模板经 any 通道喂给 useTable，官方推荐用法）
  const columns = useMemo(
    () =>
      helper.columns([
        helper.accessor("calledAt", {
          header: "时间",
          cell: (c) => (
            <span title={c.getValue()} className="whitespace-nowrap">
              {formatInstant(c.getValue())}
            </span>
          ),
        }),
      helper.accessor("toolName", {
        header: "工具",
        cell: (c) => (
          <span className="whitespace-nowrap">
            {c.getValue()}
            {c.row.original.mcp && (
              <span className="ml-1.5 rounded border border-[color:var(--color-line)] px-1 text-[10px] text-[color:var(--color-ink-faint)]">
                MCP
              </span>
            )}
          </span>
        ),
      }),
      helper.accessor("args", {
        header: "参数预览",
        enableSorting: false,
        cell: (c) => (
          <code title={c.getValue() ?? undefined} className="font-[family-name:var(--font-mono)] text-[11px]">
            {preview(c.getValue(), 48)}
          </code>
        ),
      }),
      helper.accessor("resultText", {
        header: "结果摘要",
        enableSorting: false,
        cell: (c) => (
          <span title={c.getValue() ?? undefined}>{preview(c.getValue(), 60)}</span>
        ),
      }),
      helper.accessor("asOf", {
        header: "数据截止",
        cell: (c) => (
          <span title={c.row.original.asOfKind ?? undefined} className="whitespace-nowrap">
            {c.getValue() ?? "—"}
          </span>
        ),
      }),
      helper.accessor("failed", {
        header: "结果",
        cell: (c) =>
          c.getValue() ? (
            <span className="text-[color:var(--color-up)]">失败</span>
          ) : (
            <span className="text-[color:var(--color-down)]">成功</span>
          ),
      }),
      helper.accessor("durationMs", {
        header: "时延(ms)",
        cell: (c) => <span className="tabular-nums">{c.getValue() ?? "—"}</span>,
      }),
      ]),
    [],
  );

  const table = useTable({ features: FEATURES, columns, data: data?.items ?? [] });
  const totalPages = data ? Math.max(1, Math.ceil(data.total / PAGE_SIZE)) : 1;

  return (
    <section aria-label="工具调用明细" className="mt-7">
      <h3 className="text-[14px] font-medium text-[color:var(--color-ink-dim)]">工具调用明细</h3>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <input
          value={toolDraft}
          onChange={(e) => setToolDraft(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter" && !e.nativeEvent.isComposing) applyFilters();
          }}
          placeholder="工具名（精确）"
          aria-label="工具名（精确）"
          className={`${inputCls} w-40`}
        />
        <select
          value={failedDraft}
          onChange={(e) => setFailedDraft(e.target.value as "" | "true" | "false")}
          aria-label="仅失败"
          className={inputCls}
        >
          <option value="">结果不限</option>
          <option value="true">仅失败</option>
          <option value="false">仅成功</option>
        </select>
        <input
          type="date"
          value={fromDraft}
          onChange={(e) => setFromDraft(e.target.value)}
          aria-label="开始日期"
          className={inputCls}
        />
        <span className="text-[12px] text-[color:var(--color-ink-faint)]">→</span>
        <input
          type="date"
          value={toDraft}
          onChange={(e) => setToDraft(e.target.value)}
          aria-label="结束日期"
          className={inputCls}
        />
        <button type="button" onClick={applyFilters} className={ghostBtn}>
          查询
        </button>
        {data && (
          <span className="ml-auto text-[12px] text-[color:var(--color-ink-faint)]">
            第 {page + 1}/{totalPages} 页 · 共 {data.total} 条
          </span>
        )}
      </div>

      {error && (
        <p role="alert" className="mt-3 text-[13px] text-[color:var(--color-up)]">
          {error}
        </p>
      )}

      {data && data.total === 0 ? (
        <p
          data-testid="trace-empty"
          className="mt-3 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-6 text-center text-[13px] text-[color:var(--color-ink-faint)]"
        >
          暂无工具调用观测：对话产生后自动采集
        </p>
      ) : (
        <div className="mt-3 overflow-x-auto rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)]">
          <table className="w-full text-left text-[12px]">
            <thead>
              <tr className="border-b border-[color:var(--color-line)] font-[family-name:var(--font-mono)] text-[11px] tracking-wider text-[color:var(--color-ink-faint)]">
                {table.getHeaderGroups()[0].headers.map((h) => (
                  <th
                    key={h.id}
                    onClick={h.column.getToggleSortingHandler()}
                    className="cursor-pointer whitespace-nowrap px-3 py-2 font-normal"
                  >
                    {flexRender(h.column.columnDef.header, h.getContext())}
                    <span aria-hidden="true">
                      {{ asc: " ▲", desc: " ▼" }[h.column.getIsSorted() as string] ?? ""}
                    </span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {table.getRowModel().rows.map((r) => (
                <tr key={r.id} className="border-b border-[color:var(--color-line-soft)] last:border-b-0">
                  {r.getVisibleCells().map((c) => (
                    <td key={c.id} className="px-3 py-2 text-[color:var(--color-ink)]">
                      {flexRender(c.column.columnDef.cell, c.getContext())}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="mt-2 flex items-center justify-end gap-2">
        <button
          type="button"
          disabled={page === 0}
          onClick={() => setPage((p) => Math.max(0, p - 1))}
          className={ghostBtn}
        >
          上一页
        </button>
        <button
          type="button"
          disabled={data != null && (page + 1) * PAGE_SIZE >= data.total}
          onClick={() => setPage((p) => p + 1)}
          className={ghostBtn}
        >
          下一页
        </button>
      </div>
    </section>
  );
}
