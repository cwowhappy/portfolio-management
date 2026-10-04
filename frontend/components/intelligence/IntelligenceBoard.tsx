"use client";
// 情报工作台主组件（P4 Task 4）：四 tab 单页（tab useState 照 IndustryBoard 先例）+
// 顶部统一关键词输入（回车触发、作用于当前 tab 的 q 过滤）。
// 深链（F17 互跳入参）：?stock=/?industry= 读一次进过滤初值，默认落在新闻 tab。

import { useState } from "react";
import { useSearchParams } from "next/navigation";
import NewsFeedPanel from "./NewsFeedPanel";
import AnnouncementFeedPanel from "./AnnouncementFeedPanel";
import PolicyPanel from "./PolicyPanel";
import BriefArchivePanel from "./BriefArchivePanel";

type Tab = "news" | "announcements" | "policies" | "briefs";
const TABS: Array<{ key: Tab; label: string }> = [
  { key: "news", label: "新闻流" },
  { key: "announcements", label: "公告流" },
  { key: "policies", label: "政策库" },
  { key: "briefs", label: "简报归档" },
];

export default function IntelligenceBoard() {
  const sp = useSearchParams();
  const initialStock = sp.get("stock") ?? "";
  const initialIndustry = sp.get("industry") ?? "";
  const [tab, setTab] = useState<Tab>("news");
  const [keyword, setKeyword] = useState("");
  const [q, setQ] = useState("");

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6" data-testid="intel-board">
      <div className="flex flex-wrap items-center gap-4">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">情报工作台</h1>
        <input
          type="search"
          aria-label="关键词检索"
          placeholder="关键词（回车检索当前页签）"
          className="ml-auto w-64 rounded-md border border-[color:var(--color-line)] bg-transparent px-3 py-1.5 text-sm"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              // 中文 IME 组合中 Enter 用于选定候选词，不得触发检索
              if (e.nativeEvent.isComposing) return;
              setQ(keyword.trim());
            }
          }}
        />
      </div>

      {/* tab 切换照 IndustryBoard「榜单/对比」分段按钮形态；切 tab 即挂载对应 Panel（各自独立 seq 守卫） */}
      <div className="flex flex-wrap gap-2 text-sm">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            data-testid={`intel-tab-${t.key}`}
            className={`rounded-md px-3 py-1.5 ${
              tab === t.key
                ? "bg-[color:var(--color-panel)] text-[color:var(--color-ink)]"
                : "text-[color:var(--color-ink-dim)] hover:bg-[color:var(--color-panel)]/60"
            }`}
            onClick={() => setTab(t.key)}
          >
            {t.label}
          </button>
        ))}
      </div>

      {tab === "news" && (
        <NewsFeedPanel q={q} initialStock={initialStock} initialIndustry={initialIndustry} />
      )}
      {tab === "announcements" && (
        <AnnouncementFeedPanel q={q} initialStock={initialStock} />
      )}
      {tab === "policies" && <PolicyPanel q={q} />}
      {tab === "briefs" && <BriefArchivePanel q={q} />}
    </div>
  );
}
