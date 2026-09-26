"use client";

import { useEffect, useState } from "react";
import { fetchFundingEvents, fetchUnlistedCompanies, fetchUnlistedOverview } from "@/lib/industryUnlistedApi";
import type { FundingEvent, UnlistedCompany, UnlistedOverview } from "@/lib/types";
import UnlistedOverviewCard from "./UnlistedOverviewCard";
import UnlistedCompanyTable from "./UnlistedCompanyTable";
import FundingEventTable from "./FundingEventTable";
import LandscapeChart from "./LandscapeChart";

/**
 * 「未上市与融资」tab 编排（MS-10 P2）：拉三公开读端点（cancelled 模式照
 * IndustryDrilldown 成员表先例）；未策展行业空态（curatedCount=0 且事件空）给引导文案。
 * 行展开的历史事件按 companyName 预关联（F10），LandscapeChart/CurationPanel 分别由
 * Task 6/7 挂入。
 */
export default function UnlistedPanel({ industryCode, industryName }: {
  industryCode: string;
  industryName: string;
}) {
  const [overview, setOverview] = useState<UnlistedOverview | null>(null);
  const [companies, setCompanies] = useState<UnlistedCompany[]>([]);
  const [events, setEvents] = useState<FundingEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  // 请求键（=行业码）变更即在渲染期置回加载态（照 IndustryDrilldown 成员表先例，
  // 避开 react-hooks/set-state-in-effect 的 effect 内同步 setState）
  const [prevCode, setPrevCode] = useState(industryCode);
  if (prevCode !== industryCode) {
    setPrevCode(industryCode);
    setLoading(true);
  }

  useEffect(() => {
    let cancelled = false;
    Promise.all([
      fetchUnlistedOverview(industryCode),
      fetchUnlistedCompanies(industryCode),
      fetchFundingEvents(industryCode),
    ])
      .then(([o, c, e]) => {
        if (cancelled) return;
        setOverview(o);
        setCompanies(c);
        setEvents(e);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "加载失败");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [industryCode]);

  if (error) {
    return <div className="text-sm text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;
  }
  if (loading || !overview) {
    return <div className="h-40 rounded-2xl skeleton" aria-label="加载中" />;
  }

  const eventsByCompany: Record<string, FundingEvent[]> = {};
  for (const e of events) {
    (eventsByCompany[e.companyName] ??= []).push(e);
  }
  const uncurated = overview.curatedCount === 0 && events.length === 0;

  return (
    <div className="space-y-6">
      <UnlistedOverviewCard overview={overview} />
      {uncurated ? (
        <div data-testid="unlisted-empty"
          className="rounded-2xl border border-[color:var(--color-line-soft)] p-8 text-sm text-[color:var(--color-ink-dim)]">
          {industryName || industryCode} 暂未策展未上市企业。首批重点行业（电子 / 计算机 /
          电力设备 / 医药生物 / 机械设备）名单持续补充中；登录后可在下方「编辑」手动添加或
          CSV 批量导入。
        </div>
      ) : (
        <>
          <UnlistedCompanyTable companies={companies} eventsByCompany={eventsByCompany} />
          <FundingEventTable events={events} />
          <LandscapeChart industryCode={industryCode} />
        </>
      )}
    </div>
  );
}
