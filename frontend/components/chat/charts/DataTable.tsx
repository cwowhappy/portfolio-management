"use client";
// TanStack Table v9（useTable + tableFeatures；v8 的 useReactTable 不存在）。
// ⚠ v9 方法挂原型，不可解构（const { getValue } = row 会丢 this）。
import { useMemo } from "react";
import {
  useTable, tableFeatures, rowSortingFeature, createSortedRowModel,
  sortFn_alphanumeric, flexRender, columnVisibilityFeature,
} from "@tanstack/react-table";
import type { TableSpec } from "@/lib/chart-spec";

// columnVisibilityFeature：v9 的 row.getVisibleCells() 挂在该 feature 上（core 只有 getAllCells）
const FEATURES = tableFeatures({
  rowSortingFeature,
  columnVisibilityFeature,
  sortedRowModel: createSortedRowModel(),
  sortFns: { alphanumeric: sortFn_alphanumeric },   // 只注册用到的（树摇）
});

export function DataTable({ spec }: { spec: TableSpec }) {
  const columns = useMemo(
    () => spec.columns.map((c) => ({
      id: c.key,
      header: c.label,
      accessorKey: c.key,
      sortFn: c.sortable === false ? undefined : ("alphanumeric" as const), // v9 注册表要求字面量类型
      sortDescFirst: false,             // v9 数值列默认 desc-first，显式升序起步
      enableSorting: c.sortable !== false, // sortFn:undefined 不会禁排，须 enableSorting:false
    })),
    [spec],
  );
  const table = useTable({ features: FEATURES, columns, data: spec.rows });
  const alignOf = (key: string) => spec.columns.find((c) => c.key === key)?.align;
  return (
    <div className="my-1 max-h-[360px] w-full max-w-[560px] overflow-auto rounded-md border border-[color:var(--color-line-soft)]">
      <table className="w-full text-xs">
        <thead>
          <tr>
            {table.getHeaderGroups()[0].headers.map((h) => (
              <th
                key={h.id}
                onClick={h.column.getToggleSortingHandler()}
                className="sticky top-0 z-10 cursor-pointer bg-[color:var(--color-panel-2)] px-2 py-1.5 font-medium text-[color:var(--color-ink-dim)]"
                style={{ textAlign: alignOf(h.id) ?? "left" }}
              >
                {flexRender(h.column.columnDef.header, h.getContext())}
                {/* 箭头包 span：th 直属文本保持 label 原样（getByText 精确匹配可命中），箭头对读屏隐藏 */}
                <span aria-hidden="true">{{ asc: " ▲", desc: " ▼" }[h.column.getIsSorted() as string] ?? ""}</span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {table.getRowModel().rows.map((r) => (
            <tr key={r.id} className="border-t border-[color:var(--color-line-soft)]">
              {r.getVisibleCells().map((c) => (
                <td key={c.id} className="px-2 py-1 text-[color:var(--color-ink)]" style={{ textAlign: alignOf(c.column.id) ?? "left" }}>
                  {flexRender(c.column.columnDef.cell, c.getContext())}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
