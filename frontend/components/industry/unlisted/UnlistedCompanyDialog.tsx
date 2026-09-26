"use client";

import { useState } from "react";
import { saveUnlistedCompany } from "@/lib/industryCurationApi";
import { useSaveAction } from "@/lib/useSaveAction";
import { FUNDING_ROUNDS } from "@/lib/fundingRounds";
import type { UnlistedCompany } from "@/lib/types";

/**
 * 策展企业对话框（决策 #1）：新增/编辑复用——initial 有值即编辑（带 id 走 PUT）。
 * 八字段：轮次下拉用 FundingRound 15 项 label；可空字段空串归一为 null。
 * 防连点走 useSaveAction；保存成功回调 onChanged（父层重拉读侧）后关闭。
 */
export default function UnlistedCompanyDialog({ industryCode, initial, onClose, onChanged }: {
  industryCode: string;
  initial: UnlistedCompany | null;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [companyName, setCompanyName] = useState(initial?.companyName ?? "");
  const [segment, setSegment] = useState(initial?.segment ?? "");
  const [latestRound, setLatestRound] = useState(initial?.latestRound ?? "B");
  const [lastFundingDate, setLastFundingDate] = useState(initial?.lastFundingDate ?? "");
  const [totalFundingYi, setTotalFundingYi] = useState(
    initial?.totalFundingYi != null ? String(initial.totalFundingYi) : "");
  const [summary, setSummary] = useState(initial?.summary ?? "");
  const [sourceNote, setSourceNote] = useState(initial?.sourceNote ?? "");
  const { saving, error, setError, run } = useSaveAction("保存失败");

  const submit = () => {
    if (!companyName.trim()) {
      setError("企业名称必填");
      return;
    }
    void run(async () => {
      await saveUnlistedCompany({
        id: initial?.id,
        industryCode,
        companyName: companyName.trim(),
        segment: segment.trim() || null,
        latestRound,
        lastFundingDate: lastFundingDate || null,
        totalFundingYi: totalFundingYi === "" ? null : Number(totalFundingYi),
        summary: summary.trim() || null,
        sourceNote: sourceNote.trim() || null,
      });
      onChanged();
      onClose();
    });
  };

  const inputCls = "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg)] px-2 py-1 text-sm";
  return (
    <div data-testid="unlisted-company-dialog"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => { if (e.target === e.currentTarget && !saving) onClose(); }}>
      <div className="w-full max-w-lg space-y-3 rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-bg)] p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px]">
          {initial ? "编辑策展企业" : "新增策展企业"}
        </div>
        <div className="grid grid-cols-2 gap-3 text-[13px] text-[color:var(--color-ink-dim)]">
          <label className="col-span-2 flex flex-col gap-1">企业名称（必填）
            <input className={inputCls} value={companyName} onChange={(e) => setCompanyName(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1">细分赛道
            <input className={inputCls} value={segment} onChange={(e) => setSegment(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1">最新轮次
            <select className={inputCls} value={latestRound} onChange={(e) => setLatestRound(e.target.value)}>
              {FUNDING_ROUNDS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
            </select>
          </label>
          <label className="flex flex-col gap-1">最近融资日期
            <input type="date" className={inputCls} value={lastFundingDate} onChange={(e) => setLastFundingDate(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1">累计融资(亿元)
            <input type="number" step="0.01" className={inputCls} value={totalFundingYi} onChange={(e) => setTotalFundingYi(e.target.value)} />
          </label>
          <label className="col-span-2 flex flex-col gap-1">一句话简介
            <input className={inputCls} value={summary} onChange={(e) => setSummary(e.target.value)} />
          </label>
          <label className="col-span-2 flex flex-col gap-1">来源标注
            <input className={inputCls} value={sourceNote} onChange={(e) => setSourceNote(e.target.value)} />
          </label>
        </div>
        {error && <div className="text-xs text-[color:var(--color-down)]">{error}</div>}
        <div className="flex gap-2">
          <button type="button" disabled={saving}
            className="rounded-md bg-[color:var(--color-up)] px-4 py-1.5 text-sm text-white disabled:opacity-50"
            onClick={submit}>
            {saving ? "保存中…" : "保存"}
          </button>
          <button type="button" disabled={saving}
            className="rounded-md border border-[color:var(--color-line)] px-4 py-1.5 text-sm disabled:opacity-60"
            onClick={onClose}>
            取消
          </button>
        </div>
      </div>
    </div>
  );
}
