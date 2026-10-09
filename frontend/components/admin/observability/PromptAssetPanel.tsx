"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { adminApi, type PromptAsset } from "@/lib/adminApi";
import { formatInstant, hashShort } from "./format";

const ghostBtn =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-panel)] px-3 py-1.5 text-[12px] text-[color:var(--color-ink-dim)] transition-all enabled:hover:border-[color:var(--color-line)] enabled:hover:text-[color:var(--color-ink)] disabled:cursor-not-allowed disabled:opacity-40";

const inputCls =
  "rounded-md border border-[color:var(--color-line)] bg-[color:var(--color-bg)] px-2.5 py-1.5 text-[12px] text-[color:var(--color-ink)] focus:outline-none focus:border-[color:var(--color-up)]";

const assetTypeLabel: Record<PromptAsset["assetType"], string> = {
  SYSTEM_PROMPT: "系统提示词",
  TOOL_DESC: "工具描述",
  SKILL: "技能",
  INTEL_PROMPT: "资讯提示词",
  EVAL_RUBRIC: "评分规则",
};

/**
 * 观测区块④a：提示词版本链——分组倒序列表，最新版「当前」徽标；
 * 未注记（note=null）版本带徽标并可行内补注（PUT 版本行 id，需求决策 #12）。
 */
export default function PromptAssetPanel() {
  const [assets, setAssets] = useState<PromptAsset[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<{ ok: boolean; text: string } | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [draft, setDraft] = useState("");
  const [busyId, setBusyId] = useState<number | null>(null);
  const requestSeqRef = useRef(0); // 竞态守卫（沿 AnalyticsBoard.tsx:52-58）

  const load = useCallback(() => {
    const seq = ++requestSeqRef.current;
    adminApi
      .fetchPromptAssets()
      .then((view) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的加载，丢弃过期响应
        setAssets(view.assets);
        setError(null);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载提示词版本链失败");
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  async function saveNote(id: number) {
    const note = draft.trim();
    if (!note) return; // 后端 @NotBlank 同源约束
    setBusyId(id);
    setNotice(null);
    try {
      await adminApi.setPromptAssetNote(id, note);
      setEditingId(null);
      setDraft("");
      load(); // 刷新后「未注记」徽标消失
    } catch (e) {
      setNotice({ ok: false, text: e instanceof Error ? e.message : "补注失败" });
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div aria-label="提示词版本链">
      <h4 className="text-[13px] font-medium text-[color:var(--color-ink-dim)]">提示词版本链</h4>

      {error && (
        <p role="alert" className="mt-2 text-[12px] text-[color:var(--color-up)]">
          {error}
        </p>
      )}
      {notice && (
        <p role="status" className={notice.ok ? "mt-2 text-[12px] text-[color:var(--color-down)]" : "mt-2 text-[12px] text-[color:var(--color-up)]"}>
          {notice.text}
        </p>
      )}

      {assets && assets.length === 0 && (
        <p
          data-testid="prompt-assets-empty"
          className="mt-2 rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-6 text-center text-[13px] text-[color:var(--color-ink-faint)]"
        >
          暂无提示词版本登记
        </p>
      )}

      <ul className="mt-2 flex flex-col gap-3">
        {(assets ?? []).map((asset) => (
          <li
            key={`${asset.assetType}:${asset.assetKey}`}
            data-testid={`prompt-asset-${asset.assetKey}`}
            className="rounded-xl border border-[color:var(--color-line-soft)] bg-[color:var(--color-panel)] px-4 py-3"
          >
            <p className="text-[13px] text-[color:var(--color-ink)]">
              {assetTypeLabel[asset.assetType]}
              <span className="ml-2 font-[family-name:var(--font-mono)] text-[11px] text-[color:var(--color-ink-faint)]">
                {asset.assetKey}
              </span>
            </p>
            <ul className="mt-2 flex flex-col gap-1.5">
              {asset.versions.map((v) => (
                <li
                  key={v.id}
                  className="flex flex-wrap items-center gap-2 text-[12px] text-[color:var(--color-ink-dim)]"
                >
                  <span className="font-[family-name:var(--font-mono)] text-[11px] text-[color:var(--color-ink-faint)]">
                    v{v.version}
                  </span>
                  <span title={v.contentHash} className="font-[family-name:var(--font-mono)] text-[11px] text-[color:var(--color-ink-faint)]">
                    {hashShort(v.contentHash)}
                  </span>
                  <span className="text-[11px] text-[color:var(--color-ink-faint)]">{formatInstant(v.registeredAt)}</span>
                  {v.current && (
                    <span className="rounded border border-[color:var(--color-down)]/40 px-1.5 py-px text-[10px] text-[color:var(--color-down)]">
                      当前
                    </span>
                  )}
                  {v.note != null ? (
                    <span className="text-[color:var(--color-ink)]">{v.note}</span>
                  ) : (
                    <>
                      <span className="rounded border border-[color:var(--color-up)]/40 px-1.5 py-px text-[10px] text-[color:var(--color-up)]">
                        未注记
                      </span>
                      {editingId === v.id ? (
                        <span className="flex items-center gap-1.5">
                          <input
                            value={draft}
                            onChange={(e) => setDraft(e.target.value)}
                            onKeyDown={(e) => {
                              if (e.key === "Enter" && !e.nativeEvent.isComposing && busyId == null) {
                                void saveNote(v.id);
                              }
                            }}
                            aria-label="版本说明"
                            placeholder="版本变更说明"
                            className={`${inputCls} w-48`}
                            autoFocus
                          />
                          <button
                            type="button"
                            disabled={!draft.trim() || busyId === v.id}
                            onClick={() => void saveNote(v.id)}
                            className={ghostBtn}
                          >
                            保存
                          </button>
                          <button
                            type="button"
                            onClick={() => {
                              setEditingId(null);
                              setDraft("");
                            }}
                            className={ghostBtn}
                          >
                            取消
                          </button>
                        </span>
                      ) : (
                        <button
                          type="button"
                          onClick={() => {
                            setEditingId(v.id);
                            setDraft("");
                          }}
                          className={ghostBtn}
                        >
                          补注
                        </button>
                      )}
                    </>
                  )}
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </div>
  );
}
