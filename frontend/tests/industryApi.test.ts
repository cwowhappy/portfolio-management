import { afterEach, describe, expect, it, vi } from "vitest";
import { IndustryBoardItemSchema, IndustryStockSchema } from "@/lib/schemas";
import { fetchIndustryBoard, fetchIndustryStocks } from "@/lib/industryApi";

// 真实模块 + 网络层 mock（照 industryUnlistedApi.test.ts 惯例：stubGlobal fetch 构造 Response，
// 不 vi.mock 模块本身——此前组件测试全量 mock 掉 industryApi，本文件是其实际覆盖来源）。
vi.stubGlobal("fetch", vi.fn());
const fetchMock = vi.mocked(fetch);

afterEach(() => vi.clearAllMocks());

// —— 形态取服务层真实产出（dev 库最新交易日 801780 银行行：景气 FLAT、ROE/股息率缺省 null）——
const BOARD_ITEM = {
  industryCode: "801780", industryName: "银行",
  pe: 7.52, pb: 0.75, roe: null, dividendYield: null,
  pePercentile: 96.31, pbPercentile: null,
  prosperity: "FLAT",
  prosperityInputs: { roeDeltaMedian: -0.299, revenueYoyMedian: 5.48, sampleSize: 40 },
};

const STOCK = {
  stockCode: "601398", stockName: "工商银行", totalMv: 2e12, revenue: null,
  revenueReportDate: "2026-06-30", roe: 11, peTtm: 6, pb: 0.6, dividendYield: 5, prosperity: null,
};

describe("industry schemas", () => {
  it("parses board item with nullable percentile/prosperity", () => {
    const item = IndustryBoardItemSchema.parse(BOARD_ITEM);
    expect(item.prosperity).toBe("FLAT");
    expect(item.pbPercentile).toBeNull();
    expect(item.prosperityInputs?.sampleSize).toBe(40);
  });

  it("parses stock with revenue report date", () => {
    const s = IndustryStockSchema.parse(STOCK);
    expect(s.revenueReportDate).toBe("2026-06-30");
    expect(s.revenue).toBeNull();
  });

  it("rejects bad prosperity", () => {
    expect(() => IndustryStockSchema.parse({
      ...STOCK, prosperity: "SIDEWAYS",
    })).toThrow();
  });
});

describe("industryApi（REST 客户端）", () => {
  it("fetchIndustryBoard GET /api/industry/board，zod 边界校验数组并透传 null", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([BOARD_ITEM]), { status: 200 }));
    const board = await fetchIndustryBoard();
    expect(fetchMock.mock.calls[0][0]).toBe("/api/industry/board");
    expect(board).toHaveLength(1);
    expect(board[0].prosperity).toBe("FLAT");
    expect(board[0].roe).toBeNull();
  });

  it("fetch 调用参数：GET + no-store、无 body/Content-Type（get 便捷封装语义）", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
    await fetchIndustryBoard();
    const [, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(init.method).toBe("GET");
    expect(init.cache).toBe("no-store");
    expect(init.body).toBeUndefined();
    expect(init.headers).toBeUndefined();
  });

  it("fetchIndustryStocks 默认无参：query 串为空，路径带尾随 ?（当前行为钉）", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([STOCK]), { status: 200 }));
    const stocks = await fetchIndustryStocks("801780");
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry/801780/stocks?");
    expect(stocks[0].stockName).toBe("工商银行");
  });

  it("fetchIndustryStocks 拼 sortBy/sortDirection/limit query，行业码经 encodeURIComponent", async () => {
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([STOCK]), { status: 200 }));
    await fetchIndustryStocks("801780", { sortBy: "revenue", sortDirection: "ASC", limit: 50 });
    expect(fetchMock.mock.lastCall?.[0])
      .toBe("/api/industry/801780/stocks?sortBy=revenue&sortDirection=ASC&limit=50");

    // 部分参数：仅 limit 也单独成 query
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
    await fetchIndustryStocks("801780", { limit: 30 });
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry/801780/stocks?limit=30");

    // 斜杠等保留字符转义后不进路径段
    fetchMock.mockResolvedValueOnce(new Response(JSON.stringify([]), { status: 200 }));
    await fetchIndustryStocks("80/1780");
    expect(fetchMock.mock.lastCall?.[0]).toBe("/api/industry/80%2F1780/stocks?");
  });

  it("非 2xx：优先抛响应体 message（404 行业不存在）", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify({ code: "INDUSTRY_NOT_FOUND", message: "行业不存在: 999999" }), { status: 404 }));
    await expect(fetchIndustryStocks("999999")).rejects.toThrow("行业不存在: 999999");
  });

  it("非 2xx 且 body 非 JSON：回退「请求失败」", async () => {
    fetchMock.mockResolvedValueOnce(new Response("Bad Gateway", { status: 502 }));
    await expect(fetchIndustryBoard()).rejects.toThrow("请求失败");
  });

  it("响应不合 schema：抛「数据格式异常」（http 统一翻译）", async () => {
    fetchMock.mockResolvedValueOnce(
      new Response(JSON.stringify([{ ...BOARD_ITEM, pe: "7.52" }]), { status: 200 }));
    await expect(fetchIndustryBoard()).rejects.toThrow("数据格式异常");
  });
});
