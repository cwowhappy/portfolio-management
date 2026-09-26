import { afterEach, describe, expect, it, vi } from "vitest";
import {
  CurationImportResultSchema,
  FundingEventSchema,
  UnlistedCompanySchema,
  UnlistedOverviewSchema,
} from "@/lib/schemas";
import {
  fetchFundingEvents,
  fetchUnlistedCompanies,
  fetchUnlistedOverview,
} from "@/lib/industryUnlistedApi";
import {
  companiesTemplateHref,
  deleteFundingEvent,
  deleteUnlistedCompany,
  fundingEventsTemplateHref,
  importFundingEvents,
  importUnlistedCompanies,
  saveUnlistedCompany,
} from "@/lib/industryCurationApi";

vi.stubGlobal("fetch", vi.fn());
const fetchMock = vi.mocked(fetch);

afterEach(() => vi.clearAllMocks());

// —— View 形态取后端真实产出（V19 种子「示例未名科技」：全 nullable 字段置 null 的真实行）——
const COMPANY = {
  id: 5, industryCode: "801080", industryName: undefined, companyName: "示例未名科技",
  segment: "EDA 软件", latestRound: "UNKNOWN", latestRoundLabel: "未知",
  lastFundingDate: null, totalFundingYi: null, summary: "EDA 工具链早期团队",
  sourceNote: "示例种子（V19）", updatedAt: "2026-09-26T00:00:00Z",
};

describe("unlisted schemas", () => {
  it("parses company with null lastFundingDate/totalFundingYi", () => {
    const c = UnlistedCompanySchema.parse(COMPANY);
    expect(c.companyName).toBe("示例未名科技");
    expect(c.lastFundingDate).toBeNull();
    expect(c.totalFundingYi).toBeNull();
    expect(c.latestRoundLabel).toBe("未知");
  });

  it("rejects company missing latestRoundLabel dual field", () => {
    const { latestRoundLabel: _drop, ...bad } = COMPANY;
    expect(() => UnlistedCompanySchema.parse(bad)).toThrow();
  });

  it("parses funding event with nullable amount/investors/segment/sourceUrl", () => {
    const e = FundingEventSchema.parse({
      id: 1, eventDate: "2026-06-15", companyName: "示例华芯科技", round: "B", roundLabel: "B轮",
      amountYi: 8.5, investors: null, industryCode: "801080", segment: null,
      sourceTitle: "睿兽分析 2026-06 月报", sourceUrl: null, createdAt: "2026-09-26T00:00:00Z",
    });
    expect(e.roundLabel).toBe("B轮");
    expect(e.sourceUrl).toBeNull();
  });

  it("parses overview with round distribution and coverage note", () => {
    const o = UnlistedOverviewSchema.parse({
      listedCount: 120, listedMarketCapYi: 65000.00, curatedCount: 5, fundingEvents12m: 4,
      roundDistribution: [{ round: "A", count: 1 }, { round: "D", count: 3 }],
      coverageNote: "策展名单与月度摘录融资事件，非全量口径",
    });
    expect(o.roundDistribution[1].count).toBe(3);
    expect(o.coverageNote).toContain("非全量");
  });

  it("parses curation import result (upsert double count) and rejects MS-14 shape", () => {
    const r = CurationImportResultSchema.parse({
      insertedCount: 3, updatedCount: 2, rowErrors: [{ row: 0, reason: "表头不匹配" }],
    });
    expect(r.insertedCount).toBe(3);
    expect(r.rowErrors[0].row).toBe(0);
    // MS-14 单计数 importedCount 形态必须被拒（两导入结果契约不同源）
    expect(() => CurationImportResultSchema.parse({ importedCount: 3, rowErrors: [] })).toThrow();
  });
});

describe("industryUnlistedApi（公开读）", () => {
  it("三 fetch 走反代 GET 并 zod 校验数组/对象", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([COMPANY]), { status: 200 }));
    const companies = await fetchUnlistedCompanies("801080");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/industry/801080/unlisted/companies");
    expect(companies[0].companyName).toBe("示例未名科技");

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
    await fetchFundingEvents("801080");
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry/801080/unlisted/funding-events?months=24");

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
    await fetchFundingEvents("801080", 12);
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry/801080/unlisted/funding-events?months=12");

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify({
      listedCount: 0, listedMarketCapYi: 0, curatedCount: 0, fundingEvents12m: 0,
      roundDistribution: [], coverageNote: "x",
    }), { status: 200 }));
    await fetchUnlistedOverview("801080");
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry/801080/unlisted/overview");
  });

  it("行业不存在时抛后端 message（404 经 http 统一翻译）", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "INDUSTRY_NOT_FOUND", message: "行业不存在: 999999" }), { status: 404 }));
    await expect(fetchUnlistedCompanies("999999")).rejects.toThrow("行业不存在: 999999");
  });
});

describe("industryCurationApi（登录态写侧）", () => {
  const CMD = {
    industryCode: "801080", companyName: "示例新策展", segment: "光模块", latestRound: "B",
    lastFundingDate: "2026-08-15", totalFundingYi: 12.5, summary: "简介", sourceNote: "来源",
  };

  it("saveUnlistedCompany POST / PUT by id 走 JSON 请求体", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(COMPANY), { status: 200 }));
    await saveUnlistedCompany(CMD);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/industry-curation/companies");
    expect(init.method).toBe("POST");
    expect(JSON.parse(String(init.body)).companyName).toBe("示例新策展");

    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify(COMPANY), { status: 200 }));
    await saveUnlistedCompany({ ...CMD, id: 7 });
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry-curation/companies/7");
    expect((fetchMock.mock.lastCall?.[1] as RequestInit).method).toBe("PUT");
  });

  it("delete 两端点走 DELETE 路径参数（204 无体）", async () => {
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await deleteUnlistedCompany(7);
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry-curation/companies/7");

    fetchMock.mockResolvedValueOnce(new Response(null, { status: 204 }));
    await deleteFundingEvent(9);
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry-curation/funding-events/9");
  });

  it("importUnlistedCompanies 以 multipart 字段 file POST 并校验双计数契约", async () => {
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({ insertedCount: 3, updatedCount: 2, rowErrors: [] }), { status: 200 }));
    const file = new File(["industry_code\n"], "companies.csv", { type: "text/csv" });

    const result = await importUnlistedCompanies(file);

    expect(result).toEqual({ insertedCount: 3, updatedCount: 2, rowErrors: [] });
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/industry-curation/companies/import");
    expect(init.method).toBe("POST");
    expect(init.body).toBeInstanceOf(FormData);
    expect((init.body as FormData).get("file")).toBe(file);
  });

  it("importFundingEvents POST 融资事件导入端点；非 2xx 抛响应体 message", async () => {
    fetchMock.mockResolvedValueOnce(new Response(
      JSON.stringify({ insertedCount: 0, updatedCount: 0, rowErrors: [{ row: 2, reason: "轮次无效: X" }] }),
      { status: 200 }));
    const r = await importFundingEvents(new File(["event_date\n"], "events.csv"));
    expect(r.rowErrors[0].row).toBe(2);
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry-curation/funding-events/import");

    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "INDUSTRY_INVALID_CSV", message: "文件为空" }), { status: 400 }));
    await expect(importFundingEvents(new File(["x"], "a.csv"))).rejects.toThrow("文件为空");
  });

  it("模板 href 指向两模板下载端点", () => {
    expect(companiesTemplateHref()).toBe("/api/industry-curation/companies/import/template");
    expect(fundingEventsTemplateHref()).toBe("/api/industry-curation/funding-events/import/template");
  });
});
