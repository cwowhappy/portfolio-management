"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { fetchOverview, fetchPositions, fetchGroups, fetchAllocation, fetchIndustryDistribution, fetchConcentration } from "@/lib/portfolioApi";
import type { GroupView, PortfolioOverview, PositionView, AssetAllocation, IndustryDistribution, Concentration } from "@/lib/types";
import OverviewCards from "@/components/portfolio/OverviewCards";
import PositionTable from "@/components/portfolio/PositionTable";
import AllocationPie from "@/components/portfolio/AllocationPie";
import IndustryBar from "@/components/portfolio/IndustryBar";
import ConcentrationList from "@/components/portfolio/ConcentrationList";
import BuyForm from "@/components/portfolio/BuyForm";
import GroupManager from "@/components/portfolio/GroupManager";

export default function PortfolioBoard() {
  const [overview, setOverview] = useState<PortfolioOverview | null>(null);
  const [groups, setGroups] = useState<GroupView[]>([]);
  const [positions, setPositions] = useState<PositionView[]>([]);
  const [allocation, setAllocation] = useState<AssetAllocation | null>(null);
  const [industry, setIndustry] = useState<IndustryDistribution | null>(null);
  const [concentration, setConcentration] = useState<Concentration | null>(null);
  const [activeGroup, setActiveGroup] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const requestSeqRef = useRef(0);

  const reload = useCallback(() => {
    const seq = ++requestSeqRef.current;
    Promise.all([
      fetchOverview(),
      fetchGroups(),
      fetchPositions(),
      fetchAllocation(),
      fetchIndustryDistribution(),
      fetchConcentration(),
    ])
      .then(([o, g, p, a, ind, c]) => {
        if (seq !== requestSeqRef.current) return; // 已有更新的 reload，丢弃过期响应
        setOverview(o);
        setGroups(g);
        setPositions(p);
        setAllocation(a);
        setIndustry(ind);
        setConcentration(c);
      })
      .catch((e) => {
        if (seq !== requestSeqRef.current) return;
        setError(e instanceof Error ? e.message : "加载失败");
      });
  }, []);

  useEffect(() => {
    reload();
  }, [reload]);

  const shown = activeGroup == null ? positions : positions.filter((p) => p.groupId === activeGroup);

  if (error) return <div className="p-8 text-[color:var(--color-ink-dim)]">加载失败：{error}</div>;
  if (!overview) return <div className="p-8 skeleton h-40 rounded-2xl" />;

  return (
    <div className="mx-auto max-w-6xl px-6 py-8 space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="font-[family-name:var(--font-display)] text-2xl">持仓组合</h1>
      </div>
      <BuyForm groups={groups} onChanged={reload} />
      <GroupManager groups={groups} onChanged={reload} />
      {/* 分组切换 */}
      <div className="flex gap-2" data-testid="group-tabs">
        <button className="rounded-md px-3 py-1.5 text-sm" onClick={() => setActiveGroup(null)}>全部</button>
        {groups.map((g) => (
          <button key={g.id} className="rounded-md px-3 py-1.5 text-sm" onClick={() => setActiveGroup(g.id)}>
            {g.name}
          </button>
        ))}
      </div>
      <div data-testid="overview-cards">
        <OverviewCards overview={overview} />
      </div>
      <div data-testid="position-table">
        <PositionTable positions={shown} groups={groups} onChanged={reload} />
      </div>
      <div className="grid md:grid-cols-2 gap-6" data-testid="charts">
        <div data-testid="allocation"><AllocationPie allocation={allocation} /></div>
        <div data-testid="industry"><IndustryBar industry={industry} /></div>
      </div>
      <div data-testid="concentration"><ConcentrationList concentration={concentration} /></div>
    </div>
  );
}
