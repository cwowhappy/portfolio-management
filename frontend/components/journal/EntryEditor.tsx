"use client";

import { useState } from "react";
import { createEntry, updateEntry, JOURNAL_ENTRY_TYPE_LABELS, PERIOD_TYPE_LABELS, type EntryInput } from "@/lib/journalApi";
import type { JournalEntryType, JournalEntryView, PeriodType } from "@/lib/types";

const TYPES: JournalEntryType[] = ["BUY_MEMO", "SELL_MEMO", "RESEARCH_NOTE", "REVIEW"];

export default function EntryEditor({ editing, onSaved, onCancel }: {
  editing: JournalEntryView | null; onSaved: () => void; onCancel: () => void;
}) {
  const [type, setType] = useState<JournalEntryType>(editing?.type ?? "BUY_MEMO");
  const [title, setTitle] = useState(editing?.title ?? "");
  const [content, setContent] = useState(editing?.content ?? "");
  const [eventDate, setEventDate] = useState(editing?.eventDate ?? new Date().toISOString().slice(0, 10));
  const [stockCode, setStockCode] = useState(editing?.stockCode ?? "");
  const [stockName, setStockName] = useState(editing?.stockName ?? "");
  const [tradeId, setTradeId] = useState(editing?.tradeId?.toString() ?? "");
  const [targetPrice, setTargetPrice] = useState(editing?.targetPrice?.toString() ?? "");
  const [stopLoss, setStopLoss] = useState(editing?.stopLoss?.toString() ?? "");
  const [periodType, setPeriodType] = useState<PeriodType>(editing?.periodType ?? "QUARTERLY");
  const [periodStart, setPeriodStart] = useState(editing?.periodStart ?? "");
  const [periodEnd, setPeriodEnd] = useState(editing?.periodEnd ?? "");
  const [error, setError] = useState<string | null>(null);

  const isMemo = type === "BUY_MEMO" || type === "SELL_MEMO";
  const isBuyMemo = type === "BUY_MEMO";
  const isReview = type === "REVIEW";

  const save = async () => {
    setError(null);
    const input: EntryInput = {
      type,
      title: title.trim(),
      content,
      eventDate,
      stockCode: (isMemo || type === "RESEARCH_NOTE") ? (stockCode.trim() || null) : null,
      stockName: stockName.trim() || null,
      tradeId: isMemo && tradeId ? Number(tradeId) : null,
      targetPrice: isBuyMemo && targetPrice ? Number(targetPrice) : null,
      stopLoss: isBuyMemo && stopLoss ? Number(stopLoss) : null,
      periodType: isReview ? periodType : null,
      periodStart: isReview ? (periodStart || null) : null,
      periodEnd: isReview ? (periodEnd || null) : null,
    };
    if (!input.title) { setError("标题不能为空"); return; }
    if (!input.content) { setError("内容不能为空"); return; }
    // stockCode 必填：除非已提供 tradeId（后端可从交易反查标的）
    if (type === "RESEARCH_NOTE" && !stockCode.trim()) { setError("请填写股票代码"); return; }
    if (isMemo && !tradeId.trim() && !stockCode.trim()) { setError("请填写股票代码或关联交易 ID"); return; }
    // targetPrice / stopLoss 必须为正数（填了就要 >0）
    if (isBuyMemo) {
      if (targetPrice.trim() !== "" && !(Number(targetPrice) > 0)) { setError("目标价需大于 0"); return; }
      if (stopLoss.trim() !== "" && !(Number(stopLoss) > 0)) { setError("止损价需大于 0"); return; }
    }
    // 复盘：区间必填且 start ≤ end
    if (isReview) {
      if (!periodStart || !periodEnd) { setError("请填写复盘区间起止日期"); return; }
      if (periodStart > periodEnd) { setError("开始日期不能晚于结束日期"); return; }
    }
    try {
      if (editing) await updateEntry(editing.id, input);
      else await createEntry(input);
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "保存失败");
    }
  };

  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">{editing ? "编辑记录" : "新建记录"}</div>
      <div className="flex flex-wrap gap-2 mb-3">
        {TYPES.map((t) => (
          <button key={t} type="button"
            className={type === t ? "rounded-md px-3 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]" : "rounded-md px-3 py-1.5 text-sm border border-[color:var(--color-line)]"}
            onClick={() => setType(t)}>
            {JOURNAL_ENTRY_TYPE_LABELS[t]}
          </button>
        ))}
      </div>

      <div className="grid grid-cols-2 gap-3 mb-3 max-w-xl">
        <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm col-span-2" placeholder="标题" value={title} onChange={(e) => setTitle(e.target.value)} />
        {(isMemo || type === "RESEARCH_NOTE") && (
          <>
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="股票代码（如 600519）" value={stockCode} onChange={(e) => setStockCode(e.target.value)} />
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="股票名称（如 贵州茅台）" value={stockName} onChange={(e) => setStockName(e.target.value)} />
          </>
        )}
        {isMemo && (
          <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="关联交易 ID（可选）" value={tradeId} onChange={(e) => setTradeId(e.target.value)} />
        )}
        {isBuyMemo && (
          <>
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="目标价（可选）" value={targetPrice} onChange={(e) => setTargetPrice(e.target.value)} />
            <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" placeholder="止损价（可选）" value={stopLoss} onChange={(e) => setStopLoss(e.target.value)} />
          </>
        )}
        {isReview && (
          <>
            <select className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" value={periodType} onChange={(e) => setPeriodType(e.target.value as PeriodType)}>
              <option value="QUARTERLY">{PERIOD_TYPE_LABELS.QUARTERLY}</option>
              <option value="ANNUAL">{PERIOD_TYPE_LABELS.ANNUAL}</option>
            </select>
            <input aria-label="复盘开始" className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" type="date" value={periodStart} onChange={(e) => setPeriodStart(e.target.value)} />
            <input aria-label="复盘结束" className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" type="date" value={periodEnd} onChange={(e) => setPeriodEnd(e.target.value)} />
          </>
        )}
        <input className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-sm" type="date" value={eventDate} onChange={(e) => setEventDate(e.target.value)} />
      </div>

      <textarea className="w-full rounded-md border border-[color:var(--color-line)] px-3 py-2 text-sm mb-3 min-h-32" placeholder="内容（Markdown）" value={content} onChange={(e) => setContent(e.target.value)} />
      {error && <div className="text-sm text-[color:var(--color-down)] mb-3">{error}</div>}
      <div className="flex gap-2">
        <button className="rounded-md px-4 py-1.5 text-sm bg-[color:var(--color-ink)] text-[color:var(--color-bg)]" onClick={save}>{editing ? "保存修改" : "保存记录"}</button>
        {editing && <button className="rounded-md px-4 py-1.5 text-sm border border-[color:var(--color-line)]" onClick={onCancel}>取消</button>}
      </div>
    </div>
  );
}
