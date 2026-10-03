"use client";
// 通用服务端分页条（全仓首个分页控件先例，P4 Task 4）：页码窗口 ±2 + 首末页 + 间隙省略号，
// 总数文案「共 N 条 · 第 x/y 页」。page/pageSize 用后端 PageView 夹紧回显值，翻页只回调页码。
// total=0 不渲染（空态由列表侧双空态负责）。

export default function PaginationBar({
  page,
  pageSize,
  total,
  onPageChange,
}: {
  page: number;
  pageSize: number;
  total: number;
  onPageChange: (page: number) => void;
}) {
  if (total <= 0) return null;
  const totalPages = Math.max(1, Math.ceil(total / pageSize));

  // 窗口 [page-2, page+2] 夹进 [1, totalPages]，并上首末页去重排序；相邻页码差 >1 处插省略号
  const windowPages: number[] = [];
  for (let p = Math.max(1, page - 2); p <= Math.min(totalPages, page + 2); p++) windowPages.push(p);
  const anchors = Array.from(new Set([1, totalPages, ...windowPages])).sort((a, b) => a - b);
  const cells: Array<{ kind: "page"; p: number } | { kind: "ellipsis" }> = [];
  anchors.forEach((p, i) => {
    if (i > 0 && p - anchors[i - 1] > 1) cells.push({ kind: "ellipsis" });
    cells.push({ kind: "page", p });
  });

  const navBtn =
    "rounded-md border border-[color:var(--color-line)] px-2 py-1 text-xs text-[color:var(--color-ink-dim)] disabled:opacity-40 hover:border-[color:var(--color-ink-faint)]";
  const pageBtn =
    "rounded-md px-2.5 py-1 text-xs tabular text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)]";

  return (
    <div
      className="flex flex-wrap items-center gap-1.5"
      data-testid="pagination-bar"
    >
      <button
        type="button"
        aria-label="上一页"
        disabled={page <= 1}
        className={navBtn}
        onClick={() => onPageChange(page - 1)}
      >
        ‹
      </button>
      {cells.map((c, i) =>
        c.kind === "ellipsis" ? (
          <span
            key={`ellipsis-${i}`}
            className="px-1 text-xs text-[color:var(--color-ink-faint)]"
            data-testid="pagination-ellipsis"
          >
            …
          </span>
        ) : (
          <button
            key={c.p}
            type="button"
            aria-label={`第 ${c.p} 页`}
            className={
              c.p === page
                ? pageBtn + " bg-[color:var(--color-panel)] font-medium text-[color:var(--color-ink)]"
                : pageBtn
            }
            onClick={() => onPageChange(c.p)}
          >
            {c.p}
          </button>
        ),
      )}
      <button
        type="button"
        aria-label="下一页"
        disabled={page >= totalPages}
        className={navBtn}
        onClick={() => onPageChange(page + 1)}
      >
        ›
      </button>
      <span className="ml-2 text-xs tabular text-[color:var(--color-ink-faint)]" data-testid="pagination-summary">
        共 {total} 条 · 第 {page}/{totalPages} 页
      </span>
    </div>
  );
}
