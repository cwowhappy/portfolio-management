"use client";

import { useState } from "react";
import { sell, deletePosition } from "@/lib/portfolioApi";
import type { PositionView } from "@/lib/types";

const inputClass =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)] px-3 py-2 text-[14px] text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:border-[color:var(--color-up)] focus:outline-none";

export default function PositionActions({ position, onChanged }: { position: PositionView; onChanged: () => void }) {
  const [price, setPrice] = useState("");
  const [quantity, setQuantity] = useState("");
  const [busy, setBusy] = useState(false);

  async function onSell() {
    if (!price || !quantity) return;
    setBusy(true);
    try {
      await sell({
        positionId: position.id,
        tradeDate: new Date().toISOString().slice(0, 10),
        price: Number(price),
        quantity: Number(quantity),
        fee: 0,
      });
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  async function onDelete() {
    if (!confirm(`确定删除 ${position.stockName} 持仓及其交易/分红记录？`)) return;
    setBusy(true);
    try {
      await deletePosition(position.id);
      onChanged();
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex items-center gap-2 text-sm">
      <input className={`${inputClass} w-20`} placeholder="价" value={price} onChange={(e) => setPrice(e.target.value)} />
      <input className={`${inputClass} w-20`} placeholder="量" value={quantity} onChange={(e) => setQuantity(e.target.value)} />
      <button className="rounded-md bg-[color:var(--color-amber)] px-3 py-1.5 text-white" onClick={onSell} disabled={busy}>
        卖出
      </button>
      <button className="rounded-md bg-[color:var(--color-down)] px-3 py-1.5 text-white" onClick={onDelete} disabled={busy}>
        删除
      </button>
    </div>
  );
}
