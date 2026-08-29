"use client";

import { useState } from "react";
import { addCashDividend, addStockDividend, deletePosition, editTrade, fetchTrades, sell } from "@/lib/portfolioApi";
import type { PositionView } from "@/lib/types";

const inputClass =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)] px-3 py-2 text-[14px] text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:border-[color:var(--color-up)] focus:outline-none";

export default function PositionActions({ position, onChanged }: { position: PositionView; onChanged: () => void }) {
  const [price, setPrice] = useState("");
  const [quantity, setQuantity] = useState("");
  const [cashPerShare, setCashPerShare] = useState("");
  const [stockRatio, setStockRatio] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [editing, setEditing] = useState(false);
  const [editTradeId, setEditTradeId] = useState<number | null>(null);
  const [editDate, setEditDate] = useState("");
  const [editPrice, setEditPrice] = useState("");
  const [editQuantity, setEditQuantity] = useState("");
  const [editFee, setEditFee] = useState("");

  const today = new Date().toISOString().slice(0, 10);

  async function run(action: () => Promise<unknown>, failMsg: string) {
    setBusy(true);
    setError(null);
    try {
      await action();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : failMsg);
    } finally {
      setBusy(false);
    }
  }

  async function onSell() {
    if (!price || !quantity) return;
    await run(
      () => sell({ positionId: position.id, tradeDate: today, price: Number(price), quantity: Number(quantity), fee: 0 }),
      "卖出失败",
    );
  }

  async function onCashDividend() {
    if (!cashPerShare) return;
    await run(
      () => addCashDividend({ positionId: position.id, exDate: today, cashPerShare: Number(cashPerShare) }),
      "分红录入失败",
    );
  }

  async function onStockDividend() {
    if (!stockRatio) return;
    await run(
      () => addStockDividend({ positionId: position.id, exDate: today, stockRatio: Number(stockRatio) }),
      "送股录入失败",
    );
  }

  async function onEditClick() {
    setBusy(true);
    setError(null);
    try {
      const trades = await fetchTrades(position.id);
      const buyTrade = trades.find((t) => t.type === "BUY");
      if (!buyTrade) {
        setError("未找到可编辑的买入交易");
        return;
      }
      setEditTradeId(buyTrade.id);
      setEditDate(buyTrade.tradeDate);
      setEditPrice(String(buyTrade.price));
      setEditQuantity(String(buyTrade.quantity));
      setEditFee(String(buyTrade.fee));
      setEditing(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : "加载交易失败");
    } finally {
      setBusy(false);
    }
  }

  async function onEditSubmit() {
    if (editTradeId == null || !editDate || !editPrice || !editQuantity) return;
    await run(
      () =>
        editTrade(position.id, editTradeId, {
          tradeDate: editDate,
          price: Number(editPrice),
          quantity: Number(editQuantity),
          fee: Number(editFee || "0"),
        }),
      "编辑失败",
    );
  }

  async function onDelete() {
    if (!confirm(`确定删除 ${position.stockName} 持仓及其交易/分红记录？`)) return;
    await run(() => deletePosition(position.id), "删除失败");
  }

  return (
    <div className="space-y-2">
      {editing && (
        <div className="rounded-md border border-[color:var(--color-line-soft)] p-2 space-y-1.5 text-sm">
          <div className="text-xs text-[color:var(--color-ink-dim)]">编辑买入</div>
          <div className="flex flex-wrap gap-1.5">
            <input type="date" className={`${inputClass} w-32`} value={editDate} onChange={(e) => setEditDate(e.target.value)} aria-label="编辑日期" />
            <input className={`${inputClass} w-20`} placeholder="价" value={editPrice} onChange={(e) => setEditPrice(e.target.value)} aria-label="编辑价格" />
            <input className={`${inputClass} w-20`} placeholder="量" value={editQuantity} onChange={(e) => setEditQuantity(e.target.value)} aria-label="编辑数量" />
            <input className={`${inputClass} w-16`} placeholder="费" value={editFee} onChange={(e) => setEditFee(e.target.value)} aria-label="编辑手续费" />
            <button className="rounded-md bg-[color:var(--color-up)] px-2 py-1 text-white" onClick={onEditSubmit} disabled={busy}>
              保存
            </button>
            <button className="rounded-md px-2 py-1" onClick={() => setEditing(false)} disabled={busy}>
              取消
            </button>
          </div>
        </div>
      )}

      <div className="flex flex-wrap items-center gap-1.5 text-sm">
        <input className={`${inputClass} w-20`} placeholder="价" value={price} onChange={(e) => setPrice(e.target.value)} aria-label="卖价" />
        <input className={`${inputClass} w-20`} placeholder="量" value={quantity} onChange={(e) => setQuantity(e.target.value)} aria-label="卖量" />
        <button className="rounded-md bg-[color:var(--color-amber)] px-3 py-1.5 text-white" onClick={onSell} disabled={busy}>
          卖出
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-1.5 text-sm">
        <input className={`${inputClass} w-20`} placeholder="每股" value={cashPerShare} onChange={(e) => setCashPerShare(e.target.value)} aria-label="每股金额" />
        <button className="rounded-md bg-[color:var(--color-up)] px-3 py-1.5 text-white" onClick={onCashDividend} disabled={busy}>
          现金分红
        </button>
        <input className={`${inputClass} w-20`} placeholder="比例" value={stockRatio} onChange={(e) => setStockRatio(e.target.value)} aria-label="送股比例" />
        <button className="rounded-md bg-[color:var(--color-up)] px-3 py-1.5 text-white" onClick={onStockDividend} disabled={busy}>
          送股
        </button>
      </div>

      <div className="flex flex-wrap items-center gap-1.5 text-sm">
        <button className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5" onClick={onEditClick} disabled={busy}>
          编辑
        </button>
        <button className="rounded-md bg-[color:var(--color-down)] px-3 py-1.5 text-white" onClick={onDelete} disabled={busy}>
          删除
        </button>
      </div>
      {error && <div className="text-xs text-[color:var(--color-down)]">{error}</div>}
    </div>
  );
}
