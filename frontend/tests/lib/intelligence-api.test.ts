import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/lib/intelligenceApi";

function okResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

// —— 符合 zod schema 的最小合法响应（可空字段按 CS01 一律取 null 验容忍）——

const validNewsItem = {
  title: "央行宣布降准 0.5 个百分点",
  summary: "释放长期流动性约一万亿元",
  direction: null,
  importance: null,
  keyNumbers: ["降准 0.5pct"],
  stockCodes: ["601398"],
  url: "https://finance.eastmoney.com/a/1.html",
  publishedAt: "2026-09-30T08:00:00Z",
};
const validAnnouncementItem = {
  title: "2026 年半年度报告",
  stockCode: "600519",
  stockName: "贵州茅台",
  annTypes: ["PERIODIC_REPORT"],
  annTypeSource: null,
  metrics: null,
  pdfUrl: "https://pdf.dfcfw.com/pdf/1.pdf",
  publishedAt: "2026-08-30T16:00:00Z",
};
const validMetrics = {
  revenueYi: 128.56,
  netProfitYi: 31.2,
  netProfitYoyPct: 25.3,
  deductedProfitYi: 30.05,
  grossMarginPct: null,
  dividendDesc: null,
  undisclosed: ["毛利率"],
};
const validPolicyItem = {
  title: "国常会部署一揽子增量政策",
  direction: "EASING",
  strength: "HIGH",
  areas: ["货币政策"],
  summary: "降准降息托底经济",
  confidence: "HIGH",
  isPolicy: true,
  url: "https://www.gov.cn/zhengce/1",
  publishedAt: "2026-09-28T10:00:00Z",
};
const validBriefItem = {
  tradeDate: "2026-09-30",
  status: "GENERATED",
  topStocks: ["600519"],
  model: "deepseek-chat",
  generatedAt: "2026-09-30T09:30:00Z",
};
const validBriefDetail = {
  tradeDate: "2026-09-30",
  contentMd: "## 一、市场概况\n……",
  topStocks: ["600519"],
  status: "GENERATED",
  failReason: null,
  model: "deepseek-chat",
  generatedAt: "2026-09-30T09:30:00Z",
};
const validMacroOverview = {
  indicators: [
    {
      indicator: "CPI",
      value: null,
      yoy: null,
      period: "2026-08",
      periodType: "MONTH",
      series: [{ period: "2026-08", value: 0.6 }],
    },
  ],
  missing: ["LPR"],
  generatedAt: "2026-09-30T07:00:00Z",
};
const validCalendar = [
  {
    indicator: "CPI",
    expectedDate: "2026-10-09",
    frequency: "MONTH",
    sourceSite: "国家统计局",
    updatedAt: "2026-09-01T02:00:00Z",
  },
];
const validStockIntel = {
  stockCode: "600519",
  newsTotal: 1,
  news: [validNewsItem],
  announcementTotal: 1,
  announcements: [{ ...validAnnouncementItem, metrics: validMetrics }],
  policyTotal: 0,
  policyNote: "政策事件不按标的归集，请前往政策库按方向/领域检索",
  empty: false,
};
const validSubscription = {
  pushEnabled: true,
  industries: ["801010"],
  stocks: [{ code: "600519", name: null }],
  updatedAt: null,
};
const validBindingCode = { code: "482913", expiresAt: "2026-10-03T12:10:00Z" };

function dataForUrl(url: string): unknown {
  if (url.startsWith("/api/intelligence/news")) {
    return { items: [validNewsItem], total: 1, page: 1, pageSize: 20 };
  }
  if (url.startsWith("/api/intelligence/announcements")) {
    return { items: [validAnnouncementItem], total: 1, page: 1, pageSize: 20 };
  }
  if (url.startsWith("/api/intelligence/policies")) {
    return { items: [validPolicyItem], total: 1, page: 1, pageSize: 20 };
  }
  if (url.startsWith("/api/intelligence/briefs/")) return validBriefDetail;
  if (url.startsWith("/api/intelligence/briefs")) {
    return { items: [validBriefItem], total: 1, page: 1, pageSize: 20 };
  }
  if (url.startsWith("/api/intelligence/macro/calendar")) return validCalendar;
  if (url.startsWith("/api/intelligence/macro")) return validMacroOverview;
  if (url.startsWith("/api/intelligence/stocks/")) return validStockIntel;
  if (url.startsWith("/api/intelligence/subscription/binding-code")) return validBindingCode;
  if (url.startsWith("/api/intelligence/subscription")) return validSubscription;
  return {};
}

describe("情报工作台 REST 客户端（lib/intelligenceApi）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("成功时解析并返回分页信封（可空字段 null 透传：direction/importance/annTypeSource/metrics）", async () => {
    fetchMock.mockResolvedValue(okResponse(dataForUrl("/api/intelligence/news")));
    await expect(api.fetchNews()).resolves.toEqual({
      items: [validNewsItem],
      total: 1,
      page: 1,
      pageSize: 20,
    });
    fetchMock.mockResolvedValue(okResponse(dataForUrl("/api/intelligence/announcements")));
    await expect(api.fetchAnnouncements()).resolves.toEqual({
      items: [validAnnouncementItem],
      total: 1,
      page: 1,
      pageSize: 20,
    });
  });

  it("公告 metrics 已抽取时未披露数值字段与分红描述为 null 亦可解析", async () => {
    fetchMock.mockResolvedValue(okResponse(dataForUrl("/api/intelligence/stocks/600519")));
    const intel = await api.fetchStockIntel("600519");
    expect(intel.announcements[0].metrics).toEqual(validMetrics);
    expect(intel.empty).toBe(false);
  });

  it("订阅视图缺省实例 updatedAt 为 null、标的 name 可为 null", async () => {
    fetchMock.mockResolvedValue(okResponse(validSubscription));
    await expect(api.getSubscription()).resolves.toEqual(validSubscription);
  });

  it("简报详情 failReason 非 FAILED 态为 null", async () => {
    fetchMock.mockResolvedValue(okResponse(validBriefDetail));
    await expect(api.fetchBriefDetail("2026-09-30")).resolves.toEqual(validBriefDetail);
  });

  it("宏观总览 value/yoy 可空、缺失指标显式列出", async () => {
    fetchMock.mockResolvedValue(okResponse(validMacroOverview));
    await expect(api.fetchMacro()).resolves.toEqual(validMacroOverview);
  });

  it("非 2xx 且响应体带 message 时抛出该消息（简报缺档 404）", async () => {
    fetchMock.mockResolvedValue(
      okResponse({ code: "NOT_FOUND", message: "该交易日无简报档" }, 404),
    );
    await expect(api.fetchBriefDetail("2026-10-01")).rejects.toThrow("该交易日无简报档");
  });

  it("响应不符合 schema 时抛数据格式异常（分页信封缺 pageSize）", async () => {
    fetchMock.mockResolvedValue(okResponse({ items: [], total: 0, page: 1 }));
    await expect(api.fetchNews()).rejects.toThrow("数据格式异常");
  });

  it("各封装函数拼接正确的路径与查询参数", async () => {
    fetchMock.mockImplementation(async (url: string) => okResponse(dataForUrl(url)));
    await api.fetchNews({
      q: "茅台 降准",
      stock: "600519",
      industry: "801010",
      from: "2026-09-01",
      to: "2026-09-30",
      minImportance: 60,
      page: 2,
      pageSize: 50,
    });
    await api.fetchNews();
    await api.fetchAnnouncements({
      stock: "600519",
      type: "PERIODIC_REPORT",
      q: "半年报",
      from: "2026-07-01",
      to: "2026-09-30",
      major: true,
      page: 1,
      pageSize: 20,
    });
    await api.fetchPolicies({ q: "降准", from: "2026-09-01", to: "2026-09-30", direction: "EASING" });
    await api.fetchBriefs({ from: "2026-09-01", to: "2026-09-30", stock: "600519", q: "茅台" });
    await api.fetchBriefDetail("2026-09-30");
    await api.fetchMacro({ indicators: ["CPI", "PPI"], limit: 12 });
    await api.fetchMacro();
    await api.fetchMacroCalendar(14);
    await api.fetchMacroCalendar();
    await api.fetchStockIntel("600519");
    await api.getSubscription();
    await api.createBindingCode();
    await api.updateSubscription({
      pushEnabled: false,
      industries: [],
      stocks: [{ code: "600519", name: "贵州茅台" }],
    });
    await api.unbind();
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      "/api/intelligence/news?q=%E8%8C%85%E5%8F%B0%20%E9%99%8D%E5%87%86&stock=600519&industry=801010&from=2026-09-01&to=2026-09-30&minImportance=60&page=2&pageSize=50",
      "/api/intelligence/news",
      "/api/intelligence/announcements?stock=600519&type=PERIODIC_REPORT&q=%E5%8D%8A%E5%B9%B4%E6%8A%A5&from=2026-07-01&to=2026-09-30&major=true&page=1&pageSize=20",
      "/api/intelligence/policies?q=%E9%99%8D%E5%87%86&from=2026-09-01&to=2026-09-30&direction=EASING",
      "/api/intelligence/briefs?from=2026-09-01&to=2026-09-30&stock=600519&q=%E8%8C%85%E5%8F%B0",
      "/api/intelligence/briefs/2026-09-30",
      "/api/intelligence/macro?indicators=CPI,PPI&limit=12",
      "/api/intelligence/macro",
      "/api/intelligence/macro/calendar?days=14",
      "/api/intelligence/macro/calendar",
      "/api/intelligence/stocks/600519",
      "/api/intelligence/subscription",
      "/api/intelligence/subscription/binding-code",
      "/api/intelligence/subscription",
      "/api/intelligence/subscription/binding",
    ]);
    // 写方法：B1 无请求体 POST、S2 PUT JSON、B2 DELETE（列表第 13~15 次调用）
    const [, , , , , , , , , , , , postCall, putCall, deleteCall] = fetchMock.mock.calls;
    expect((postCall[1] as RequestInit).method).toBe("POST");
    expect((postCall[1] as RequestInit).body).toBeUndefined();
    expect((putCall[1] as RequestInit).method).toBe("PUT");
    expect((putCall[1] as RequestInit).body).toBe(
      JSON.stringify({ pushEnabled: false, industries: [], stocks: [{ code: "600519", name: "贵州茅台" }] }),
    );
    expect((putCall[1] as RequestInit).headers).toEqual({ "Content-Type": "application/json" });
    expect((deleteCall[1] as RequestInit).method).toBe("DELETE");
  });

  it("绑定码生成解析 code 与过期时刻（201）", async () => {
    fetchMock.mockResolvedValue(okResponse(validBindingCode, 201));
    await expect(api.createBindingCode()).resolves.toEqual(validBindingCode);
  });

  it("解绑 204 返回 undefined", async () => {
    fetchMock.mockResolvedValue(okResponse(undefined, 204));
    await expect(api.unbind()).resolves.toBeUndefined();
  });
});
