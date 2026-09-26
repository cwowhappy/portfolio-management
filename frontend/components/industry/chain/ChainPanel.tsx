"use client";

import { useCallback, useEffect, useState } from "react";
import { fetchIndustryChains } from "@/lib/industryChainApi";
import { useAuth } from "@/lib/auth";
import type { ChainView } from "@/lib/types";
import ChainGraphCard from "./ChainGraphCard";
import ChainEditorDialog from "./ChainEditorDialog";

/**
 * 「产业链」tab 编排（MS-10 F11）：拉公开 chains 端点（成员派生关联，整包返回）；
 * 未映射行业空态引导文案。登录态挂「新增产业链」入口与每链行内「编辑」（对话框本体
 * 挂本层，新增/编辑复用同一 ChainEditorDialog）；变更后 load() 直查库刷新（§九#4）。
 */
export default function ChainPanel({ industryCode, industryName }: {
  industryCode: string;
  industryName: string;
}) {
  const { user } = useAuth();
  const [chains, setChains] = useState<ChainView[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  // 对话框态：editTarget undefined=关 / null=新增 / 有值=行编辑
  const [editTarget, setEditTarget] = useState<ChainView | null | undefined>(undefined);

  // 请求键变更渲染期置回加载态（照 UnlistedPanel 先例，避开 set-state-in-effect）
  const [prevCode, setPrevCode] = useState(industryCode);
  if (prevCode !== industryCode) {
    setPrevCode(industryCode);
    setChains(null);
    setError(null);
  }

  // 刷新（编辑变更后直查库；不开头 setLoading——数据原位替换，无骨架屏闪烁）
  const load = useCallback(() => {
    let cancelled = false;
    fetchIndustryChains(industryCode)
      .then((c) => { if (!cancelled) { setChains(c); setError(null); } })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "加载失败"); });
    return () => { cancelled = true; };
  }, [industryCode]);

  useEffect(() => load(), [industryCode, load]);

  if (error) {
    return <div className="text-sm text-[color:var(--color-ink-dim)]">产业链加载失败：{error}</div>;
  }
  if (chains == null) {
    return <div className="h-40 rounded-2xl skeleton" aria-label="加载中" />;
  }

  return (
    <div className="space-y-6">
      {user && (
        <button type="button" data-testid="chain-add"
          className="rounded-md border border-[color:var(--color-line)] px-3 py-1.5 text-xs hover:bg-[color:var(--color-panel)]"
          onClick={() => setEditTarget(null)}>
          新增产业链
        </button>
      )}
      {chains.length === 0 ? (
        <div data-testid="chain-empty"
          className="rounded-2xl border border-[color:var(--color-line-soft)] p-8 text-sm text-[color:var(--color-ink-dim)]">
          {industryName || industryCode}：该行业暂无产业链映射。产业链由成员行业归属自动派生——
          登录后可在上方「新增产业链」维护链/环节/成员，含上市成员的行业下钻页将自动展示。
        </div>
      ) : (
        chains.map((chain) => (
          <div key={chain.id} className="space-y-1">
            <ChainGraphCard chain={chain} />
            {user && (
              <button type="button" data-testid={`chain-edit-${chain.id}`}
                className="rounded-md border border-[color:var(--color-line)] px-3 py-1 text-xs hover:bg-[color:var(--color-panel)]"
                onClick={() => setEditTarget(chain)}>
                编辑
              </button>
            )}
          </div>
        ))
      )}
      {editTarget !== undefined && (
        <ChainEditorDialog
          industryCode={industryCode}
          initial={editTarget}
          onClose={() => setEditTarget(undefined)}
          onChanged={load} />
      )}
    </div>
  );
}
