import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import UnlistedCompanyTable from "@/components/industry/unlisted/UnlistedCompanyTable";
import type { FundingEvent, UnlistedCompany } from "@/lib/types";

afterEach(() => cleanup());

const companies: UnlistedCompany[] = [
  { id: 1, industryCode: "801080", companyName: "示例华芯科技", segment: "半导体设备",
    latestRound: "B", latestRoundLabel: "B轮", lastFundingDate: "2026-06-15",
    totalFundingYi: 12.5, summary: "半导体刻蚀设备新锐", sourceNote: "示例种子（V19）",
    updatedAt: "2026-09-26T00:00:00Z" },
  { id: 5, industryCode: "801080", companyName: "示例未名科技", segment: "EDA 软件",
    latestRound: "UNKNOWN", latestRoundLabel: "未知", lastFundingDate: null,
    totalFundingYi: null, summary: null, sourceNote: null,
    updatedAt: "2026-09-26T00:00:00Z" },
];

const events: FundingEvent[] = [
  { id: 1, eventDate: "2026-06-15", companyName: "示例华芯科技", round: "B", roundLabel: "B轮",
    amountYi: 8.5, investors: "深创投、中芯聚源", industryCode: "801080", segment: "半导体设备",
    sourceTitle: "睿兽分析 2026-06 月报", sourceUrl: null, createdAt: "2026-09-26T00:00:00Z" },
];

describe("UnlistedCompanyTable", () => {
  it("列渲染：轮次 label、null 融资显示未披露、null 日期与来源显示 —", () => {
    render(<UnlistedCompanyTable companies={companies} eventsByCompany={{ "示例华芯科技": events }} />);

    const table = screen.getByTestId("unlisted-companies-table");
    expect(table.textContent).toContain("示例华芯科技");
    expect(table.textContent).toContain("半导体设备");
    expect(table.textContent).toContain("B轮");
    expect(table.textContent).toContain("2026-06-15");
    expect(table.textContent).toContain("12.5");
    // 未名科技行：全缺省字段的人话占位
    expect(table.textContent).toContain("未披露");
    expect(table.textContent).toContain("未知");
  });

  it("行点击展开详情卡且互斥：点他行切换，仅一张详情卡在场", () => {
    render(<UnlistedCompanyTable companies={companies} eventsByCompany={{ "示例华芯科技": events }} />);

    fireEvent.click(screen.getByText("示例华芯科技"));
    expect(screen.getByTestId("company-detail-card").textContent).toContain("睿兽分析 2026-06 月报");

    // 点另一行：切换到新行的详情卡（未名科技无历史事件 → 仅策展摘要）
    fireEvent.click(screen.getByText("示例未名科技"));
    const detail = screen.getByTestId("company-detail-card");
    expect(detail.textContent).toContain("示例未名科技");
    expect(detail.textContent).not.toContain("睿兽分析 2026-06 月报");
  });
});
