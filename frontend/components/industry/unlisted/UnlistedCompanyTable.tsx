"use client";

import { useState } from "react";
import type { FundingEvent, UnlistedCompany } from "@/lib/types";
import CompanyDetailCard from "./CompanyDetailCard";

/**
 * 策展名单表（F07）：行点击展开企业详情卡（F10，单展开互斥）；轮次渲染 label、
 * 累计融资/日期 null 显示「未披露/—」。排序由后端口径决定（lastFundingDate DESC）。
 */
export default function UnlistedCompanyTable({ companies, eventsByCompany }: {
  companies: UnlistedCompany[];
  eventsByCompany: Record<string, FundingEvent[]>;
}) {
  const [expanded, setExpanded] = useState<string | null>(null);

  return (
    <table data-testid="unlisted-companies-table" className="w-full text-sm">
      <thead>
        <tr className="text-left text-xs text-[color:var(--color-ink-faint)]">
          <th className="py-1 pr-4 font-normal">企业</th>
          <th className="py-1 pr-4 font-normal">细分赛道</th>
          <th className="py-1 pr-4 font-normal">最新轮次</th>
          <th className="py-1 pr-4 font-normal">最近融资</th>
          <th className="py-1 pr-4 text-right font-normal">累计融资(亿元)</th>
          <th className="py-1 font-normal">来源</th>
        </tr>
      </thead>
      <tbody>
        {companies.map((c) => (
          <Row key={c.id} company={c}
            events={eventsByCompany[c.companyName] ?? []}
            expanded={expanded === c.companyName}
            onToggle={() => setExpanded(expanded === c.companyName ? null : c.companyName)} />
        ))}
      </tbody>
    </table>
  );
}

function Row({ company, events, expanded, onToggle }: {
  company: UnlistedCompany;
  events: FundingEvent[];
  expanded: boolean;
  onToggle: () => void;
}) {
  return (
    <>
      <tr className="cursor-pointer border-t border-[color:var(--color-line-soft)] hover:bg-[color:var(--color-panel)]/50"
        onClick={onToggle}>
        <td className="py-1.5 pr-4">{company.companyName}</td>
        <td className="py-1.5 pr-4">{company.segment ?? "—"}</td>
        <td className="py-1.5 pr-4">{company.latestRoundLabel}</td>
        <td className="py-1.5 pr-4 tabular">{company.lastFundingDate ?? "—"}</td>
        <td className="py-1.5 pr-4 text-right tabular">
          {company.totalFundingYi == null ? "未披露" : company.totalFundingYi}
        </td>
        <td className="py-1.5">{company.sourceNote ?? "—"}</td>
      </tr>
      {expanded && (
        <tr className="border-t border-[color:var(--color-line-soft)]">
          <td colSpan={6} className="px-2 py-2">
            <CompanyDetailCard company={company} events={events} />
          </td>
        </tr>
      )}
    </>
  );
}
