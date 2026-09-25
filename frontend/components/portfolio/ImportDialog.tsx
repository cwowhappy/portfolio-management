"use client";

import { useState } from "react";
import { importCsv, templateHref } from "@/lib/portfolioImportApi";
import type { ImportResult } from "@/lib/types";
import { useSaveAction } from "@/lib/useSaveAction";

/**
 * CSV 批量导入对话框：选文件 → multipart 上传，结果三态——
 * 全成功（「成功导入 N 笔」+ onImported 刷新父层）/ 行级错误清单（行号+原因表，不刷新）/
 * 文件级错误（useSaveAction 行内文案）。防连点走 useSaveAction。
 */
export default function ImportDialog({ onImported }: { onImported: () => void }) {
  const [open, setOpen] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ImportResult | null>(null);
  const { saving, error, setError, run, reset } = useSaveAction("导入失败");

  const openDialog = () => {
    setFile(null);
    setResult(null);
    reset(); // 清除上一轮 saving/error 残留（防迟到响应翻转重开后的视图）
    setOpen(true);
  };

  const submit = () => {
    if (!file) return; // 未选文件不可提交（按钮已 disabled，此处双保险）
    void run(async () => {
      const r = await importCsv(file);
      setResult(r);
      // 全成功才刷新父层；行级错误=该文件未导入，无需重拉
      if (r.rowErrors.length === 0) onImported();
    });
  };

  const backToForm = () => {
    setFile(null);
    setResult(null);
  };

  return (
    <>
      <button type="button" data-testid="import-open"
        className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-xs hover:bg-[color:var(--color-panel)]"
        onClick={openDialog}>
        批量导入
      </button>
      {open && (
        <div data-testid="import-dialog"
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
          onClick={(e) => { if (e.target === e.currentTarget && !saving) setOpen(false); }}>
          <div className="w-full max-w-xl rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-bg)] p-5 space-y-3">
            <div className="font-[family-name:var(--font-display)] text-[15px]">批量导入持仓</div>
            {result ? (
              <>
                {result.rowErrors.length > 0 ? (
                  <>
                    <div className="text-sm text-[color:var(--color-down)]">
                      {result.importedCount > 0
                        ? `成功导入 ${result.importedCount} 笔，以下 ${result.rowErrors.length} 行未导入：`
                        : `以下 ${result.rowErrors.length} 行未导入：`}
                    </div>
                    <table data-testid="import-row-errors" aria-label="import-row-errors" className="w-full text-sm">
                      <thead>
                        <tr className="text-left text-xs text-[color:var(--color-ink-faint)]">
                          <th className="py-1 pr-4 font-normal">行号</th>
                          <th className="py-1 font-normal">原因</th>
                        </tr>
                      </thead>
                      <tbody>
                        {result.rowErrors.map((e, i) => (
                          <tr key={`${e.row}-${i}`} className="border-t border-[color:var(--color-line-soft)]">
                            <td className="py-1 pr-4 tabular">{e.row}</td>
                            <td className="py-1">{e.reason}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </>
                ) : (
                  <div className="text-sm" data-testid="import-success">成功导入 {result.importedCount} 笔</div>
                )}
                <div className="flex gap-2">
                  <button type="button"
                    className="rounded-md bg-[color:var(--color-ink)] px-4 py-1.5 text-sm text-[color:var(--color-bg)]"
                    onClick={backToForm}>
                    再导一次
                  </button>
                  <button type="button" disabled={saving}
                    className="rounded-md border border-[color:var(--color-line)] px-4 py-1.5 text-sm disabled:opacity-60"
                    onClick={() => setOpen(false)}>
                    关闭
                  </button>
                </div>
              </>
            ) : (
              <>
                <div className="text-xs text-[color:var(--color-ink-dim)]">
                  上传 CSV 批量录入交易与现金流水（单文件上限 1MB，上限 2000 行）；格式先看
                  <a className="mx-1 underline" href={templateHref()} download>下载模板</a>。
                </div>
                <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
                  CSV 文件
                  <input
                    type="file" accept=".csv,text/csv" className="text-sm text-[color:var(--color-ink)]"
                    onChange={(e) => { setFile(e.target.files?.[0] ?? null); setError(null); }}
                  />
                </label>
                {error && <div className="text-xs text-[color:var(--color-down)]">{error}</div>}
                <div className="flex gap-2">
                  <button type="button" disabled={saving || !file}
                    className="rounded-md bg-[color:var(--color-up)] px-4 py-1.5 text-sm text-white disabled:opacity-50"
                    onClick={submit}>
                    {saving ? "导入中…" : "导入"}
                  </button>
                  <button type="button" disabled={saving}
                    className="rounded-md border border-[color:var(--color-line)] px-4 py-1.5 text-sm disabled:opacity-60"
                    onClick={() => setOpen(false)}>
                    取消
                  </button>
                </div>
              </>
            )}
          </div>
        </div>
      )}
    </>
  );
}
