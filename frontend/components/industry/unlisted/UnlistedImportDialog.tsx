"use client";

import { useState } from "react";
import {
  companiesTemplateHref,
  fundingEventsTemplateHref,
  importFundingEvents,
  importUnlistedCompanies,
} from "@/lib/industryCurationApi";
import type { CurationImportResult } from "@/lib/types";
import { useSaveAction } from "@/lib/useSaveAction";

/**
 * 策展双目标 CSV 导入对话框（决策 #3/#5b）：顶部切换「策展企业 / 融资事件」两模板两端点。
 * 结果三态（照 ImportDialog）：成功「新增 N 条，更新 M 条」双计数 + onImported 刷新 /
 * 行级错误清单（curation-import-row-errors，行号+原因）/ 文件级错误行内文案。
 */
export default function UnlistedImportDialog({ onClose, onImported }: {
  onClose: () => void;
  onImported: () => void;
}) {
  const [target, setTarget] = useState<"companies" | "fundingEvents">("companies");
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<CurationImportResult | null>(null);
  const { saving, error, setError, run, reset } = useSaveAction("导入失败");

  const isCompanies = target === "companies";
  const templateHref = isCompanies ? companiesTemplateHref() : fundingEventsTemplateHref();
  const templateName = isCompanies ? "unlisted-companies-template.csv" : "industry-funding-events-template.csv";

  const openDialog = () => {
    setFile(null);
    setResult(null);
    reset();
  };

  const submit = () => {
    if (!file) return;
    void run(async () => {
      const r = isCompanies ? await importUnlistedCompanies(file) : await importFundingEvents(file);
      setResult(r);
      // 全成功才刷新父层；行级错误 = 该文件未导入，无需重拉
      if (r.rowErrors.length === 0) onImported();
    });
  };

  return (
    <div data-testid="curation-import-dialog"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => { if (e.target === e.currentTarget && !saving) onClose(); }}>
      <div className="w-full max-w-xl space-y-3 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-bg)] p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px]">批量导入</div>
        {result ? (
          <>
            {result.rowErrors.length > 0 ? (
              <>
                <div className="text-sm text-[color:var(--color-down)]">
                  以下 {result.rowErrors.length} 行未导入（该文件未生效）：
                </div>
                <table data-testid="curation-import-row-errors" aria-label="curation-import-row-errors" className="w-full text-sm">
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
              <div className="text-sm" data-testid="curation-import-success">
                新增 {result.insertedCount} 条，更新 {result.updatedCount} 条
              </div>
            )}
            <div className="flex gap-2">
              <button type="button"
                className="rounded-md bg-[color:var(--color-ink)] px-4 py-1.5 text-sm text-[color:var(--color-bg)]"
                onClick={openDialog}>
                再导一次
              </button>
              <button type="button" disabled={saving}
                className="rounded-md border border-[color:var(--color-line)] px-4 py-1.5 text-sm disabled:opacity-60"
                onClick={onClose}>
                关闭
              </button>
            </div>
          </>
        ) : (
          <>
            <div className="flex gap-2 text-[13px]">
              <label className="flex items-center gap-1">
                <input type="radio" checked={isCompanies} onChange={() => { setTarget("companies"); setFile(null); setError(null); }} />
                策展企业
              </label>
              <label className="flex items-center gap-1">
                <input type="radio" checked={!isCompanies} onChange={() => { setTarget("fundingEvents"); setFile(null); setError(null); }} />
                融资事件
              </label>
            </div>
            <div className="text-xs text-[color:var(--color-ink-dim)]">
              上传 CSV（单文件上限 1MB、上限 2000 行，upsert 幂等——重复导入更新而非报错）；格式先看
              <a className="mx-1 underline" href={templateHref} download>{templateName}</a>。
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
                onClick={onClose}>
                取消
              </button>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
