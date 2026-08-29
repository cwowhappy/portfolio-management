import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import * as api from "@/lib/api";

function okResponse(data: unknown, status = 200): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    json: vi.fn().mockResolvedValue(data),
  } as unknown as Response;
}

// 符合 zod schema 的最小合法响应
const validHealth = {
  status: "up",
  llm: {
    provider: "deepseek",
    model: "deepseek-chat",
    baseUrl: "https://api.deepseek.com",
    keyConfigured: true,
  },
  market: { ok: true, latencyMs: 42 },
};
const validQuote = {
  code: "600519",
  name: "贵州茅台",
  price: 1415,
  change: 15,
  changePct: 1.07,
  open: 1410,
  high: 1428,
  low: 1405.5,
  prevClose: 1400,
  volume: 2345600,
  amount: 3.3e9,
  pe: 21.35,
  pb: 7.82,
  time: "2026-08-18 15:00",
};
const validOverview = { time: "2026-08-18 15:00", indices: [] };
const validFinancials = {
  code: "600519",
  name: "贵州茅台",
  pe: null,
  pb: null,
  indicators: [],
};

function dataForUrl(url: string): unknown {
  if (url.startsWith("/api/market/overview")) return validOverview;
  if (url.startsWith("/api/market/quote")) return validQuote;
  if (url.startsWith("/api/market/kline")) return [];
  if (url.startsWith("/api/market/financials")) return validFinancials;
  if (url.startsWith("/api/market/news")) return [];
  if (url.startsWith("/api/market/search")) return [];
  return {};
}

describe("行情 REST 客户端（lib/api）", () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal("fetch", fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("成功时解析并返回 JSON", async () => {
    fetchMock.mockResolvedValue(okResponse(validHealth));
    await expect(api.fetchHealth()).resolves.toEqual(validHealth);
  });

  it("fetchHealth 打 /api/agent/status（完整结构；/api/agent/health 是纯 liveness）", async () => {
    fetchMock.mockResolvedValue(okResponse(validHealth));
    await api.fetchHealth();
    expect(fetchMock.mock.calls[0][0]).toBe("/api/agent/status");
  });

  it("非 2xx 且响应体带 message 时抛出该消息", async () => {
    fetchMock.mockResolvedValue(okResponse({ message: "上游超时" }, 502));
    await expect(api.fetchHealth()).rejects.toThrow("上游超时");
  });

  it("非 2xx 且响应体无 message 时抛默认消息", async () => {
    fetchMock.mockResolvedValue(okResponse({}, 502));
    await expect(api.fetchHealth()).rejects.toThrow("请求失败");
  });

  it("非 2xx 且响应体不是 JSON 时抛默认消息", async () => {
    const bad = {
      ok: false,
      status: 502,
      json: vi.fn().mockRejectedValue(new SyntaxError("Unexpected token")),
    } as unknown as Response;
    fetchMock.mockResolvedValue(bad);
    await expect(api.fetchHealth()).rejects.toThrow("请求失败");
  });

  it("响应不符合 schema 时抛数据格式异常", async () => {
    fetchMock.mockResolvedValue(okResponse({ status: "up" })); // 缺 llm/market
    await expect(api.fetchHealth()).rejects.toThrow("数据格式异常");
  });

  it("各封装函数拼接正确的路径与查询参数", async () => {
    fetchMock.mockImplementation(async (url: string) => okResponse(dataForUrl(url)));
    await api.fetchOverview();
    await api.fetchQuote("600519");
    await api.fetchKline("600519");
    await api.fetchKline("600519", "week", 60);
    await api.fetchFinancials("600519");
    await api.fetchNews("600519");
    await api.fetchNews("600519", 5);
    await api.searchStocks("茅 台");
    expect(fetchMock.mock.calls.map((c) => c[0])).toEqual([
      "/api/market/overview",
      "/api/market/quote/600519",
      "/api/market/kline/600519?period=day&limit=120",
      "/api/market/kline/600519?period=week&limit=60",
      "/api/market/financials/600519",
      "/api/market/news/600519?limit=10",
      "/api/market/news/600519?limit=5",
      "/api/market/search?q=%E8%8C%85%20%E5%8F%B0",
    ]);
  });

  it("所有请求都禁用缓存（cache: no-store）", async () => {
    fetchMock.mockResolvedValue(okResponse(validQuote));
    await api.fetchQuote("600519");
    expect(fetchMock.mock.calls[0][1]).toEqual({ cache: "no-store" });
  });
});
