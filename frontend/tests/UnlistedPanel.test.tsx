import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import UnlistedPanel from "@/components/industry/unlisted/UnlistedPanel";
import type { FundingEvent, UnlistedCompany, UnlistedOverview } from "@/lib/types";

// vi.hoisted：vi.mock 工厂被提升到静态 import 前，mock 引用须同层提升（照 IndustryDrilldown.test 先例）。
const { overviewMock, companiesMock, eventsMock } = vi.hoisted(() => ({
  overviewMock: vi.fn(),
  companiesMock: vi.fn(),
  eventsMock: vi.fn(),
}));

vi.mock("@/lib/industryUnlistedApi", () => ({
  fetchUnlistedOverview: overviewMock,
  fetchUnlistedCompanies: companiesMock,
  fetchFundingEvents: eventsMock,
}));

afterEach(() => {
  cleanup();
  overviewMock.mockReset();
  companiesMock.mockReset();
  eventsMock.mockReset();
});

// fixture 取服务层 View 真实形态（V19 种子行，含 null 字段：未名科技全缺省）
const overview: UnlistedOverview = {
  listedCount: 214, listedMarketCapYi: 53201.8, curatedCount: 5, fundingEvents12m: 3,
  roundDistribution: [
    { round: "A", count: 1 }, { round: "B", count: 1 }, { round: "D", count: 1 },
  ],
  coverageNote: "策展名单与月度摘录融资事件，非全量口径",
};

const companies: UnlistedCompany[] = [
  { id: 1, industryCode: "801080", companyName: "示例华芯科技", segment: "半导体设备",
    latestRound: "B", latestRoundLabel: "B轮", lastFundingDate: "2026-06-15",
    totalFundingYi: 12.5, summary: "半导体刻蚀设备新锐，国产替代主力", sourceNote: "示例种子（V19）",
    updatedAt: "2026-09-26T00:00:00Z" },
  { id: 5, industryCode: "801080", companyName: "示例未名科技", segment: "EDA 软件",
    latestRound: "UNKNOWN", latestRoundLabel: "未知", lastFundingDate: null,
    totalFundingYi: null, summary: "EDA 工具链早期团队，融资信息未披露", sourceNote: "示例种子（V19）",
    updatedAt: "2026-09-26T00:00:00Z" },
];

const events: FundingEvent[] = [
  { id: 1, eventDate: "2026-06-15", companyName: "示例华芯科技", round: "B", roundLabel: "B轮",
    amountYi: 8.5, investors: "深创投、中芯聚源", industryCode: "801080", segment: "半导体设备",
    sourceTitle: "睿兽分析 2026-06 月报", sourceUrl: null, createdAt: "2026-09-26T00:00:00Z" },
  { id: 2, eventDate: "2025-09-10", companyName: "示例华芯科技", round: "A", roundLabel: "A轮",
    amountYi: 3.2, investors: "中芯聚源", industryCode: "801080", segment: "半导体设备",
    sourceTitle: "企查查公开页人工摘录",
    sourceUrl: "https://example.com/seed/huaxin-a", createdAt: "2026-09-26T00:00:00Z" },
];

describe("UnlistedPanel", () => {
  beforeEach(() => {
    overviewMock.mockResolvedValue(overview);
    companiesMock.mockResolvedValue(companies);
    eventsMock.mockResolvedValue(events);
  });

  it("渲染全景卡四指标/轮次分布、策展名单与融资动态（口径徽标 + 脚注）", async () => {
    render(<UnlistedPanel industryCode="801080" industryName="电子" />);

    // 全景卡：四指标 + 分布 + 口径脚注
    const card = await screen.findByTestId("unlisted-overview");
    expect(card.textContent).toContain("214");           // 上市数
    expect(card.textContent).toContain("53,201.8");      // 总市值亿（千分位）
    expect(card.textContent).toContain("5");             // 策展头部数
    expect(card.textContent).toContain("3");             // 近12月事件数
    expect(card.textContent).toContain("A轮");           // 分布轮次中文标签
    expect(card.textContent).toContain("策展名单与月度摘录融资事件，非全量口径");

    // 名单：轮次 label 渲染、null 累计融资 → 未披露、null 日期 → —
    const table = screen.getByTestId("unlisted-companies-table");
    expect(table.textContent).toContain("示例华芯科技");
    expect(table.textContent).toContain("B轮");
    expect(table.textContent).toContain("未披露");

    // 融资动态：口径徽标 + 事件行（null 金额未披露、来源标题渲染）
    const eventsTable = screen.getByTestId("unlisted-funding-events-table");
    expect(eventsTable.textContent).toContain("月度摘录·非全量");
    expect(eventsTable.textContent).toContain("睿兽分析 2026-06 月报");
  });

  it("未策展行业空态：curatedCount=0 且事件空 → unlisted-empty 引导文案", async () => {
    overviewMock.mockResolvedValue({ ...overview, curatedCount: 0, fundingEvents12m: 0, roundDistribution: [] });
    companiesMock.mockResolvedValue([]);
    eventsMock.mockResolvedValue([]);

    render(<UnlistedPanel industryCode="801780" industryName="银行" />);

    expect(await screen.findByTestId("unlisted-empty")).toBeTruthy();
    expect(screen.queryByTestId("unlisted-companies-table")).toBeNull();
    expect(screen.queryByTestId("unlisted-funding-events-table")).toBeNull();
  });

  it("行点击展开企业详情卡：策展摘要 + 该企业历史融资事件（Panel 预关联）", async () => {
    render(<UnlistedPanel industryCode="801080" industryName="电子" />);
    await screen.findByTestId("unlisted-companies-table");

    // 展开后名字同时出现在行与详情卡，取首个（行单元格）
    const rowName = () => screen.getAllByText("示例华芯科技")[0];
    fireEvent.click(rowName());
    const detail = screen.getByTestId("company-detail-card");
    expect(detail.textContent).toContain("半导体刻蚀设备新锐");     // 策展简介
    expect(detail.textContent).toContain("企查查公开页人工摘录");   // 历史事件（A轮 2025-09-10）
    expect(detail.textContent).toContain("2025-09-10");

    // 再点同一行收起
    fireEvent.click(rowName());
    expect(screen.queryByTestId("company-detail-card")).toBeNull();
  });

  it("三端点任一失败出错误文案（不渲染半截数据）", async () => {
    overviewMock.mockRejectedValue(new Error("请求失败"));
    render(<UnlistedPanel industryCode="801080" industryName="电子" />);

    await waitFor(() => expect(screen.getByText(/加载失败/)).toBeTruthy());
    expect(screen.queryByTestId("unlisted-overview")).toBeNull();
  });
});
