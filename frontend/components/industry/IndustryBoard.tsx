"use client";

import { useCallback, useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { fetchIndustryBoard } from "@/lib/industryApi";
import { fetchIndustryWatch, unwatchIndustry, watchIndustry } from "@/lib/industryWatchApi";
import { useAuth } from "@/lib/auth";
import type { IndustryBoardItem } from "@/lib/types";
import IndustryBoardTable from "./IndustryBoardTable";
import IndustryCompareView from "./IndustryCompareView";
import Disclaimer from "@/components/Disclaimer";
import ValuationHeatmap from "./ValuationHeatmap";

export default function IndustryBoard() {
  const router = useRouter();
  const { user } = useAuth();
  const [items, setItems] = useState<IndustryBoardItem[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [view, setView] = useState<"board" | "compare">("board");
  const [watchedCodes, setWatchedCodes] = useState<Set<string>>(new Set());
  const [watchError, setWatchError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    fetchIndustryBoard()
      .then((d) => { if (!cancelled) setItems(d); })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : "加载失败"); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  // 登录后拉一次关注代码集合（⭐ 实心判断）；未登录保持空集合（照 ScreenerBoard.reloadWatchlist 模式）。
  // setState 全部在 promise 回调里（避开 effect 内同步 setState 的 cascading render 警告）
  const reloadWatch = useCallback(() => {
    return Promise.resolve().then(() => {
      if (!user) {
        setWatchedCodes(new Set());
        return;
      }
      return fetchIndustryWatch()
        .then((rows) => setWatchedCodes(new Set(rows.map((r) => r.industryCode))))
        .catch(() => {});
    });
  }, [user]);

  useEffect(() => { reloadWatch(); }, [reloadWatch]);

  // 未登录点 ⭐ → 登录后回到 /industry；已登录 → 关注/取关并同步集合（后端幂等，本地按当前态切换）
  const onToggleWatch = (industryCode: string) => {
    if (!user) {
      router.push("/login?redirect=/industry");
      return;
    }
    setWatchError(null);
    const wasWatched = watchedCodes.has(industryCode);
    (wasWatched ? unwatchIndustry(industryCode) : watchIndustry(industryCode))
      .then(() => {
        setWatchedCodes((prev) => {
          const next = new Set(prev);
          if (wasWatched) next.delete(industryCode);
          else next.add(industryCode);
          return next;
        });
      })
      .catch((e) =>
        setWatchError(e instanceof Error ? e.message : wasWatched ? "取消关注失败" : "关注失败"));
  };

  if (error) {
    return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;
  }
  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <div className="flex items-center gap-4">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">行业估值</h1>
        {/* 「榜单 / 对比」分段切换（按钮样式照 ScreenerBoard tab 先例） */}
        <div className="flex gap-2 text-sm">
          <button
            data-testid="view-board"
            className={`rounded-md px-3 py-1.5 ${view === "board"
              ? "bg-[color:var(--color-panel)] text-[color:var(--color-ink)]"
              : "text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)]/60"}`}
            onClick={() => setView("board")}
          >
            榜单
          </button>
          <button
            data-testid="view-compare"
            className={`rounded-md px-3 py-1.5 ${view === "compare"
              ? "bg-[color:var(--color-panel)] text-[color:var(--color-ink)]"
              : "text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)]/60"}`}
            onClick={() => setView("compare")}
          >
            对比
          </button>
        </div>
      </div>
      {watchError && <div className="text-sm text-[color:var(--color-up)]">{watchError}</div>}
      {loading ? (
        <div className="h-40 rounded-2xl skeleton" aria-label="加载中" />
      ) : view === "compare" ? (
        // 对比视图消费同一份 board items（无新 API）；关注集与 ⭐ 回调共享，未登录跳登录逻辑复用
        <IndustryCompareView items={items}
          watchedCodes={watchedCodes} onToggleWatch={onToggleWatch} user={user} />
      ) : (
        <div className="grid md:grid-cols-2 gap-6">
          <IndustryBoardTable items={items}
            onSelect={(code) => router.push(`/industry/${code}`)}
            watchedCodes={watchedCodes} onToggleWatch={onToggleWatch} />
          <ValuationHeatmap industries={items} />
        </div>
      )}
      <Disclaimer />
    </div>
  );
}
