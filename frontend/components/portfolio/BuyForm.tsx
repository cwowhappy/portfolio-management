"use client";

import { useState } from "react";
import { buy } from "@/lib/portfolioApi";
import { searchStocks } from "@/lib/api";
import type { GroupView, StockHit } from "@/lib/types";

const inputClass =
  "w-full rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)] px-3 py-2 text-[14px] text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:border-[color:var(--color-up)] focus:outline-none";

export default function BuyForm({ groups, onChanged }: { groups: GroupView[]; onChanged: () => void }) {
  const [code, setCode] = useState("");
  const [name, setName] = useState("");
  const [groupId, setGroupId] = useState<number | "">(groups[0]?.id ?? "");
  const [date, setDate] = useState(new Date().toISOString().slice(0, 10));
  const [price, setPrice] = useState("");
  const [quantity, setQuantity] = useState("");
  const [fee, setFee] = useState("0");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onSearch() {
    try {
      const hits: StockHit[] = await searchStocks(code);
      if (hits[0]) {
        setName(hits[0].name);
        setCode(hits[0].code);
      }
    } catch {
      // 搜索失败忽略
    }
  }

  async function onSubmit() {
    setBusy(true);
    setError(null);
    try {
      await buy({
        groupId: Number(groupId),
        stockCode: code,
        stockName: name,
        tradeDate: date,
        price: Number(price),
        quantity: Number(quantity),
        fee: Number(fee),
      });
      setPrice("");
      setQuantity("");
      setFee("0");
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "买入失败");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5">
      <div className="font-[family-name:var(--font-display)] text-[15px] mb-3">买入</div>
      <div className="grid grid-cols-2 md:grid-cols-6 gap-3 text-sm">
        <label className="col-span-2 flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
          代码
          <input className={inputClass} value={code} onChange={(e) => setCode(e.target.value)} onBlur={onSearch} />
        </label>
        <label className="col-span-2 flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
          名称
          <input className={inputClass} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
          分组
          <select className={inputClass} value={groupId} onChange={(e) => setGroupId(Number(e.target.value))}>
            {groups.filter((g) => g.type === "ACCOUNT").map((g) => (
              <option key={g.id} value={g.id}>{g.name}</option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
          日期
          <input type="date" className={inputClass} value={date} onChange={(e) => setDate(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
          价格
          <input className={inputClass} value={price} onChange={(e) => setPrice(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
          数量
          <input className={inputClass} value={quantity} onChange={(e) => setQuantity(e.target.value)} />
        </label>
        <label className="flex flex-col gap-1.5 text-[13px] text-[color:var(--color-ink-dim)]">
          手续费
          <input className={inputClass} value={fee} onChange={(e) => setFee(e.target.value)} />
        </label>
        <button
          className="self-end rounded-md bg-[color:var(--color-up)] px-4 py-2 text-white disabled:opacity-50"
          onClick={onSubmit}
          disabled={busy || !code || !name || !price || !quantity}
        >
          {busy ? "提交中…" : "买入"}
        </button>
      </div>
      {error && <div className="mt-2 text-xs text-[color:var(--color-down)]">{error}</div>}
    </div>
  );
}
