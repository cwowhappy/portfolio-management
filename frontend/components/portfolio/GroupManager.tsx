"use client";

import { useState } from "react";
import { addCashTransaction, createGroup, renameGroup } from "@/lib/portfolioApi";
import type { GroupView } from "@/lib/types";

const inputClass =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg-soft)] px-3 py-2 text-[14px] text-[color:var(--color-ink)] placeholder:text-[color:var(--color-ink-faint)] focus:border-[color:var(--color-up)] focus:outline-none";

export default function GroupManager({ groups, onChanged }: { groups: GroupView[]; onChanged: () => void }) {
  const [name, setName] = useState("");
  const [type, setType] = useState<"ACCOUNT" | "TAG">("ACCOUNT");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [renamingId, setRenamingId] = useState<number | null>(null);
  const [renameName, setRenameName] = useState("");

  const [txGroupId, setTxGroupId] = useState<number | null>(null);
  const [txType, setTxType] = useState<"DEPOSIT" | "WITHDRAW">("DEPOSIT");
  const [txAmount, setTxAmount] = useState("");
  const [txDate, setTxDate] = useState(new Date().toISOString().slice(0, 10));
  const [txBusy, setTxBusy] = useState(false);
  const [txError, setTxError] = useState<string | null>(null);

  const accountGroups = groups.filter((g) => g.type === "ACCOUNT");
  const effectiveTxGroupId: number | null = txGroupId ?? (accountGroups.length > 0 ? accountGroups[0].id : null);

  async function onCreate() {
    if (!name.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await createGroup({ name: name.trim(), type });
      setName("");
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "创建失败");
    } finally {
      setBusy(false);
    }
  }

  function startRename(g: GroupView) {
    setRenamingId(g.id);
    setRenameName(g.name);
  }

  async function onRename(g: GroupView) {
    if (!renameName.trim()) return;
    setBusy(true);
    setError(null);
    try {
      await renameGroup(g.id, { name: renameName.trim() });
      setRenamingId(null);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "改名失败");
    } finally {
      setBusy(false);
    }
  }

  async function onSubmitCashTx() {
    const gid = effectiveTxGroupId;
    if (gid == null || !txAmount) return;
    setTxBusy(true);
    setTxError(null);
    try {
      await addCashTransaction({ groupId: gid, type: txType, amount: Number(txAmount), txDate });
      setTxAmount("");
      onChanged();
    } catch (e) {
      setTxError(e instanceof Error ? e.message : "录入失败");
    } finally {
      setTxBusy(false);
    }
  }

  return (
    <div className="rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-panel)]/70 p-5 space-y-4">
      <div className="font-[family-name:var(--font-display)] text-[15px]">分组</div>
      <div className="flex gap-2 text-sm">
        <input
          className={`${inputClass} flex-1 min-w-0`}
          placeholder="分组名（如 华泰）"
          value={name}
          onChange={(e) => setName(e.target.value)}
        />
        <select className={inputClass} value={type} onChange={(e) => setType(e.target.value as "ACCOUNT" | "TAG")}>
          <option value="ACCOUNT">账户</option>
          <option value="TAG">标签</option>
        </select>
        <button
          className="rounded-md bg-[color:var(--color-up)] px-3 py-1.5 text-white disabled:opacity-50"
          onClick={onCreate}
          disabled={busy || !name.trim()}
        >
          新建
        </button>
      </div>
      {error && <div className="text-xs text-[color:var(--color-down)]">{error}</div>}

      <div className="rounded-xl border border-[color:var(--color-line-soft)] p-3 space-y-2">
        <div className="text-sm text-[color:var(--color-ink-dim)]">现金转入 / 转出</div>
        <div className="flex flex-wrap gap-2 text-sm">
          <select
            className={inputClass}
            value={effectiveTxGroupId ?? ""}
            onChange={(e) => setTxGroupId(e.target.value === "" ? null : Number(e.target.value))}
            aria-label="现金账户"
          >
            {accountGroups.length === 0 && <option value="">无账户分组</option>}
            {accountGroups.map((g) => (
              <option key={g.id} value={g.id}>{g.name}</option>
            ))}
          </select>
          <select
            className={inputClass}
            value={txType}
            onChange={(e) => setTxType(e.target.value as "DEPOSIT" | "WITHDRAW")}
            aria-label="转入转出"
          >
            <option value="DEPOSIT">转入</option>
            <option value="WITHDRAW">转出</option>
          </select>
          <input
            className={`${inputClass} w-32`}
            placeholder="金额"
            value={txAmount}
            onChange={(e) => setTxAmount(e.target.value)}
            aria-label="金额"
          />
          <input
            type="date"
            className={inputClass}
            value={txDate}
            onChange={(e) => setTxDate(e.target.value)}
            aria-label="日期"
          />
          <button
            className="rounded-md bg-[color:var(--color-up)] px-3 py-1.5 text-white disabled:opacity-50"
            onClick={onSubmitCashTx}
            disabled={txBusy || effectiveTxGroupId == null || !txAmount}
          >
            录入
          </button>
        </div>
        {txError && <div className="text-xs text-[color:var(--color-down)]">{txError}</div>}
      </div>

      <ul className="space-y-1 text-sm">
        {groups.map((g) => (
          <li key={g.id} className="flex justify-between items-center">
            {renamingId === g.id ? (
              <span className="flex items-center gap-2">
                <input
                  className={`${inputClass} w-40`}
                  value={renameName}
                  onChange={(e) => setRenameName(e.target.value)}
                  aria-label="改名输入"
                />
                <button
                  className="rounded-md bg-[color:var(--color-up)] px-2 py-1 text-xs text-white disabled:opacity-50"
                  onClick={() => onRename(g)}
                  disabled={busy || !renameName.trim()}
                >
                  保存
                </button>
                <button className="rounded-md px-2 py-1 text-xs" onClick={() => setRenamingId(null)}>
                  取消
                </button>
              </span>
            ) : (
              <span>
                {g.name}
                <span className="ml-2 text-xs text-[color:var(--color-ink-faint)]">{g.type === "ACCOUNT" ? "账户" : "标签"}</span>
              </span>
            )}
            {renamingId !== g.id && (
              <span className="flex items-center gap-2">
                {g.type === "ACCOUNT" && <span className="tabular">现金 {g.cashBalance.toFixed(2)}</span>}
                <button
                  className="rounded-md border border-[color:var(--color-line)] px-2 py-1 text-xs"
                  onClick={() => startRename(g)}
                >
                  改名
                </button>
              </span>
            )}
          </li>
        ))}
      </ul>
    </div>
  );
}
