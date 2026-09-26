"use client";

import { useCallback, useEffect, useState } from "react";
import { fetchFundingEvents, fetchUnlistedCompanies, fetchUnlistedOverview } from "@/lib/industryUnlistedApi";
import { deleteUnlistedCompany } from "@/lib/industryCurationApi";
import { useAuth } from "@/lib/auth";
import type { FundingEvent, UnlistedCompany, UnlistedOverview } from "@/lib/types";
import UnlistedOverviewCard from "./UnlistedOverviewCard";
import UnlistedCompanyTable from "./UnlistedCompanyTable";
import FundingEventTable from "./FundingEventTable";
import LandscapeChart from "./LandscapeChart";
import CurationPanel from "./CurationPanel";
import UnlistedCompanyDialog from "./UnlistedCompanyDialog";
import UnlistedImportDialog from "./UnlistedImportDialog";

/**
 * 「未上市与融资」tab 编排（MS-10 P2）：拉三公开读端点（cancelled 模式照
 * IndustryDrilldown 成员表先例）；未策展行业空态（curatedCount=0 且事件空）给引导文案。
 * 行展开的历史事件按 companyName 预关联（F10）。登录态挂 CurationPanel（决策 #1 内嵌
 * 管理）+ 表格行内编辑/删除；新增/行编辑复用同一 UnlistedCompanyDialog；变更后 load()
 * 直查库刷新（读侧无缓存，§九#4）。
 */
export default function UnlistedPanel({ industryCode, industryName }: {
  industryCode: string;
  industryName: string;
}) {
  const { user } = useAuth();
  const [overview, setOverview] = useState<UnlistedOverview | null>(null);
  const [companies, setCompanies] = useState<UnlistedCompany[]>([]);
  const [events, setEvents] = useState<FundingEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  // 对话框态：editTarget undefined=关 / null=新增 / 有值=行编辑
  const [editTarget, setEditTarget] = useState<UnlistedCompany | null | undefined>(undefined);
  const [importOpen, setImportOpen] = useState(false);
  const [deleteError, setDeleteError] = useState<string | null>(null);

  // 请求键（=行业码）变更即在渲染期置回加载态（照 IndustryDrilldown 成员表先例，
  // 避开 react-hooks/set-state-in-effect 的 effect 内同步 setState）
  const [prevCode, setPrevCode] = useState(industryCode);
  if (prevCode !== industryCode) {
    setPrevCode(industryCode);
    setLoading(true);
  }

  // 刷新（策展变更后直查库；不在开头 setLoading——数据原位替换，无骨架屏闪烁）
  const load = useCallback(() => {
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
        setError(null);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : "加载失败");
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [industryCode]);

  useEffect(() => load(), [industryCode, load]);

  const onDelete = (company: UnlistedCompany) => {
    setDeleteError(null);
    deleteUnlistedCompany(company.id)
      .then(() => load())
      .catch((e) => setDeleteError(e instanceof Error ? e.message : "删除失败"));
  };

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
      {user && (
        <div className="flex items-center gap-3">
          <CurationPanel
            industryCode={industryCode}
            onAdd={() => setEditTarget(null)}
            onImport={() => setImportOpen(true)} />
          {deleteError && <span className="text-xs text-[color:var(--color-down)]">{deleteError}</span>}
        </div>
      )}
      {uncurated ? (
        <div data-testid="unlisted-empty"
          className="rounded-2xl border border-[color:var(--color-line-soft)] p-8 text-sm text-[color:var(--color-ink-dim)]">
          {industryName || industryCode} 暂未策展未上市企业。首批重点行业（电子 / 计算机 /
          电力设备 / 医药生物 / 机械设备）名单持续补充中；登录后可在上方「编辑」手动添加或
          CSV 批量导入。
        </div>
      ) : (
        <>
          <UnlistedCompanyTable
            companies={companies}
            eventsByCompany={eventsByCompany}
            actions={user ? { onEdit: (c) => setEditTarget(c), onDelete } : undefined} />
          <FundingEventTable events={events} />
        </>
      )}
      <LandscapeChart industryCode={industryCode} />
      {editTarget !== undefined && (
        <UnlistedCompanyDialog
          industryCode={industryCode}
          initial={editTarget}
          onClose={() => setEditTarget(undefined)}
          onChanged={load} />
      )}
      {importOpen && (
        <UnlistedImportDialog
          onClose={() => setImportOpen(false)}
          onImported={load} />
      )}
    </div>
  );
}
