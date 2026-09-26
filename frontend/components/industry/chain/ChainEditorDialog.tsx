"use client";

import { useEffect, useState } from "react";
import { fetchUnlistedCompanies } from "@/lib/industryUnlistedApi";
import { saveChain, deleteChain } from "@/lib/industryCurationApi";
import { useSaveAction } from "@/lib/useSaveAction";
import type { ChainView, UnlistedCompany } from "@/lib/types";

const TIERS = [
  { value: "UPSTREAM", label: "上游" },
  { value: "MIDSTREAM", label: "中游" },
  { value: "DOWNSTREAM", label: "下游" },
] as const;

const MEMBER_TYPES = [
  { value: "LISTED", label: "上市" },
  { value: "UNLISTED", label: "未上市" },
] as const;

type MemberDraft = { memberType: string; stockCode: string; unlistedCompanyId: number | null; displayName: string };
type StageDraft = { tier: string; name: string; members: MemberDraft[] };

/**
 * 产业链全文档编辑器（MS-10 P3，设计规格 §四写侧）：stages+members 嵌套整体替换——
 * 新增/编辑复用（initial 有值即编辑带 PUT id）；环节支持增删/上移下移（sortOrder 由
 * 提交时数组序派生）；成员行按类型切换「证券代码」或「策展企业下拉」（选企业自动带出
 * 展示名）。删除链按钮 window.confirm 确认（照 GroupManager 先例）。防连点 useSaveAction。
 */
export default function ChainEditorDialog({ industryCode, initial, onClose, onChanged }: {
  industryCode: string;
  initial: ChainView | null;
  onClose: () => void;
  onChanged: () => void;
}) {
  const [name, setName] = useState(initial?.name ?? "");
  const [description, setDescription] = useState(initial?.description ?? "");
  const [stages, setStages] = useState<StageDraft[]>(() => initial?.stages.map((s) => ({
    tier: s.tier,
    name: s.name,
    members: s.members.map((m) => ({
      memberType: m.memberType,
      stockCode: m.stockCode ?? "",
      unlistedCompanyId: m.unlistedCompanyId,
      displayName: m.displayName,
    })),
  })) ?? [newStage()]);
  const [companies, setCompanies] = useState<UnlistedCompany[]>([]);
  const { saving, error, setError, run } = useSaveAction("保存失败");

  // 未上市成员下拉数据源（当前行业策展名单；失败静默——空下拉不影响上市成员编辑）
  useEffect(() => {
    let cancelled = false;
    fetchUnlistedCompanies(industryCode)
      .then((c) => { if (!cancelled) setCompanies(c); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [industryCode]);

  const updateStage = (i: number, patch: Partial<StageDraft>) =>
    setStages((prev) => prev.map((s, si) => (si === i ? { ...s, ...patch } : s)));
  const moveStage = (i: number, dir: -1 | 1) =>
    setStages((prev) => {
      const j = i + dir;
      if (j < 0 || j >= prev.length) return prev;
      const next = [...prev];
      [next[i], next[j]] = [next[j], next[i]];
      return next;
    });
  const updateMember = (i: number, j: number, patch: Partial<MemberDraft>) =>
    setStages((prev) => prev.map((s, si) => si !== i ? s : {
      ...s, members: s.members.map((m, mi) => (mi === j ? { ...m, ...patch } : m)),
    }));

  const submit = () => {
    if (!name.trim()) { setError("链名必填"); return; }
    for (const [i, s] of stages.entries()) {
      if (!s.name.trim()) { setError(`第 ${i + 1} 个环节缺少环节名`); return; }
      if (s.members.length === 0) { setError(`环节「${s.name || i + 1}」至少需要一个成员`); return; }
      for (const [j, m] of s.members.entries()) {
        if (!m.displayName.trim()) {
          setError(`环节「${s.name}」第 ${j + 1} 个成员缺少展示名`); return;
        }
        if (m.memberType === "LISTED" && !m.stockCode.trim()) {
          setError(`上市成员「${m.displayName || j + 1}」必须提供证券代码`); return;
        }
        if (m.memberType === "UNLISTED" && m.unlistedCompanyId == null) {
          setError(`未上市成员「${m.displayName || j + 1}」必须选择策展企业`); return;
        }
      }
    }
    void run(async () => {
      await saveChain({
        id: initial?.id,
        name: name.trim(),
        description: description.trim() || null,
        stages: stages.map((s, i) => ({
          tier: s.tier,
          name: s.name.trim(),
          sortOrder: i + 1,
          members: s.members.map((m) => ({
            memberType: m.memberType,
            stockCode: m.memberType === "LISTED" ? m.stockCode.trim() : null,
            unlistedCompanyId: m.memberType === "UNLISTED" ? m.unlistedCompanyId : null,
            displayName: m.displayName.trim(),
          })),
        })),
      });
      onChanged();
      onClose();
    });
  };

  const onDelete = () => {
    if (initial?.id == null) return;
    if (!window.confirm(`确认删除产业链「${initial.name}」？环节与成员将一并删除。`)) return;
    void run(async () => {
      await deleteChain(initial.id);
      onChanged();
      onClose();
    });
  };

  const inputCls = "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg)] px-2 py-1 text-sm";
  return (
    <div data-testid="chain-editor-dialog"
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
      onClick={(e) => { if (e.target === e.currentTarget && !saving) onClose(); }}>
      <div className="max-h-[85vh] w-full max-w-2xl space-y-3 overflow-y-auto rounded-2xl border border-[color:var(--color-line)] bg-[color:var(--color-bg)] p-5">
        <div className="font-[family-name:var(--font-display)] text-[15px]">
          {initial ? "编辑产业链" : "新增产业链"}
        </div>
        <div className="grid grid-cols-2 gap-3 text-[13px] text-[color:var(--color-ink-dim)]">
          <label className="flex flex-col gap-1">链名（必填）
            <input className={inputCls} value={name} onChange={(e) => setName(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1">描述
            <input className={inputCls} value={description} onChange={(e) => setDescription(e.target.value)} />
          </label>
        </div>

        {stages.map((stage, i) => (
          <div key={i} data-testid={`stage-box-${i}`}
            className="space-y-2 rounded-xl border border-[color:var(--color-line-soft)] p-3">
            <div className="flex items-center gap-2 text-[13px]">
              <select aria-label={`环节层级 ${i + 1}`} className={inputCls} value={stage.tier}
                onChange={(e) => updateStage(i, { tier: e.target.value })}>
                {TIERS.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
              <input data-testid={`stage-name-${i}`} className={`${inputCls} flex-1`}
                placeholder="环节名（如 锂矿）" value={stage.name}
                onChange={(e) => updateStage(i, { name: e.target.value })} />
              <button type="button" aria-label={`环节上移 ${i + 1}`} disabled={i === 0 || saving}
                className="rounded border border-[color:var(--color-line)] px-2 py-1 text-xs disabled:opacity-40"
                onClick={() => moveStage(i, -1)}>↑</button>
              <button type="button" aria-label={`环节下移 ${i + 1}`}
                disabled={i === stages.length - 1 || saving}
                className="rounded border border-[color:var(--color-line)] px-2 py-1 text-xs disabled:opacity-40"
                onClick={() => moveStage(i, 1)}>↓</button>
              <button type="button" aria-label={`删除环节 ${i + 1}`} disabled={stages.length === 1 || saving}
                className="rounded border border-[color:var(--color-line)] px-2 py-1 text-xs text-[color:var(--color-down)] disabled:opacity-40"
                onClick={() => setStages((prev) => prev.filter((_, si) => si !== i))}>删除环节</button>
            </div>
            {stage.members.map((m, j) => (
              <div key={j} className="flex flex-wrap items-center gap-2 text-[13px]">
                <select data-testid={`member-type-${i}-${j}`} className={inputCls} value={m.memberType}
                  onChange={(e) => updateMember(i, j, e.target.value === "LISTED"
                    ? { memberType: "LISTED", unlistedCompanyId: null }
                    : { memberType: "UNLISTED", stockCode: "" })}>
                  {MEMBER_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                </select>
                {m.memberType === "LISTED" ? (
                  <input data-testid={`member-code-${i}-${j}`} className={inputCls} placeholder="证券代码"
                    value={m.stockCode} onChange={(e) => updateMember(i, j, { stockCode: e.target.value })} />
                ) : (
                  <select data-testid={`member-company-${i}-${j}`} className={inputCls}
                    value={m.unlistedCompanyId ?? ""}
                    onChange={(e) => {
                      const id = e.target.value === "" ? null : Number(e.target.value);
                      const picked = companies.find((c) => c.id === id);
                      updateMember(i, j, {
                        unlistedCompanyId: id,
                        displayName: picked ? picked.companyName : m.displayName,
                      });
                    }}>
                    <option value="">选择策展企业</option>
                    {companies.map((c) => <option key={c.id} value={c.id}>{c.companyName}</option>)}
                  </select>
                )}
                <input data-testid={`member-name-${i}-${j}`} className={`${inputCls} flex-1`} placeholder="展示名"
                  value={m.displayName} onChange={(e) => updateMember(i, j, { displayName: e.target.value })} />
                <button type="button" aria-label={`删除成员 ${i + 1}-${j + 1}`} disabled={saving}
                  className="rounded border border-[color:var(--color-line)] px-2 py-1 text-xs text-[color:var(--color-down)]"
                  onClick={() => setStages((prev) => prev.map((s, si) => si !== i ? s
                    : { ...s, members: s.members.filter((_, mi) => mi !== j) }))}>删</button>
              </div>
            ))}
            <button type="button" data-testid={`stage-add-member-${i}`} disabled={saving}
              className="rounded border border-[color:var(--color-line)] px-2 py-1 text-xs"
              onClick={() => updateStage(i, {
                members: [...stage.members, { memberType: "LISTED", stockCode: "", unlistedCompanyId: null, displayName: "" }],
              })}>添加成员</button>
          </div>
        ))}

        <div className="flex gap-2">
          <button type="button" data-testid="chain-add-stage" disabled={saving}
            className="rounded border border-[color:var(--color-line)] px-3 py-1.5 text-xs"
            onClick={() => setStages((prev) => [...prev, newStage()])}>添加环节</button>
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
            onClick={onClose}>取消</button>
          {initial && (
            <button type="button" data-testid="chain-delete" disabled={saving}
              className="ml-auto rounded-md border border-[color:var(--color-line)] px-4 py-1.5 text-sm text-[color:var(--color-down)]"
              onClick={onDelete}>删除链</button>
          )}
        </div>
      </div>
    </div>
  );
}

function newStage(): StageDraft {
  return { tier: "UPSTREAM", name: "", members: [{ memberType: "LISTED", stockCode: "", unlistedCompanyId: null, displayName: "" }] };
}
