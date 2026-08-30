import { afterEach, describe, it, expect, vi } from "vitest";
import {
  addCashDividend, addCashTransaction, addStockDividend, buy, createGroup, deleteGroup,
  deletePosition, editTrade, fetchAllocation, fetchConcentration, fetchGroups,
  fetchIndustryDistribution, fetchOverview, fetchPositions, fetchTrades, renameGroup, sell,
} from "@/lib/portfolioApi";

const positionJson = {
  id: 5, groupId: 1, stockCode: "600519", stockName: "贵州茅台",
  quantity: 60, avgCost: 110, price: 120, marketValue: 7200,
  floatingPnl: 600, pnlRatio: 10, realizedPnl: 400, totalBuyCost: 11000, cumulativeCashDividend: 0,
};

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("portfolioApi", () => {
  it("fetchOverview 解析总览", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ totalAssets: 12000, totalCost: 10000, totalPnl: 2000, todayPnl: 0, cashTotal: 0, totalCashDividend: 150, positionCount: 1, groupCount: 1 }),
    }));
    const data = await fetchOverview();
    expect(data.totalAssets).toBe(12000);
    expect(data.totalCashDividend).toBe(150);
    expect(data.positionCount).toBe(1);
  });

  it("fetchGroups 解析分组列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => [{ id: 1, name: "华泰", type: "ACCOUNT", positionCount: 0, cashBalance: 0 }],
    }));
    const groups = await fetchGroups();
    expect(groups[0].name).toBe("华泰");
  });

  it("renameGroup 走 PUT 并解析分组", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ id: 1, name: "东财", type: "ACCOUNT", positionCount: 0, cashBalance: 0 }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const group = await renameGroup(1, { name: "东财" });
    expect(group.name).toBe("东财");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/groups/1");
    expect(init.method).toBe("PUT");
    expect(init.body).toBe(JSON.stringify({ name: "东财" }));
  });

  it("addCashTransaction 走 POST 并解析流水", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 201,
      json: async () => ({ id: 9, groupId: 1, type: "DEPOSIT", amount: 10000, txDate: "2026-08-27", note: null }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const tx = await addCashTransaction({ groupId: 1, type: "DEPOSIT", amount: 10000, txDate: "2026-08-27" });
    expect(tx.type).toBe("DEPOSIT");
    expect(tx.amount).toBe(10000);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/cash-transactions");
    expect(init.method).toBe("POST");
  });

  it("fetchTrades 解析交易列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => [{ id: 11, type: "BUY", tradeDate: "2026-08-27", price: 110, quantity: 100, fee: 0 }],
    }));
    const trades = await fetchTrades(5);
    expect(trades[0].type).toBe("BUY");
    expect(trades[0].price).toBe(110);
  });

  it("editTrade 走 PUT 并解析持仓", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => positionJson });
    vi.stubGlobal("fetch", fetchMock);
    const pos = await editTrade(5, 11, { tradeDate: "2026-08-27", price: 110, quantity: 100, fee: 0 });
    expect(pos.avgCost).toBe(110);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/positions/5/trades/11");
    expect(init.method).toBe("PUT");
  });

  it("fetchPositions 不带 groupId 时查询全部持仓", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [positionJson] });
    vi.stubGlobal("fetch", fetchMock);
    const positions = await fetchPositions();
    expect(positions[0].stockCode).toBe("600519");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/positions");
    expect(init.method).toBe("GET");
  });

  it("fetchPositions 带 groupId 时拼查询串", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => [] });
    vi.stubGlobal("fetch", fetchMock);
    await fetchPositions(3);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/portfolio/positions?groupId=3");
  });

  it("createGroup 走 POST 并解析新分组", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 201,
      json: async () => ({ id: 2, name: "华泰", type: "ACCOUNT", positionCount: 0, cashBalance: 0 }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const group = await createGroup({ name: "华泰", type: "ACCOUNT" });
    expect(group.id).toBe(2);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/groups");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({ name: "华泰", type: "ACCOUNT" }));
    expect(init.headers).toEqual({ "Content-Type": "application/json" });
  });

  it("deleteGroup 走 DELETE 并以 204 返回 undefined", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204, json: async () => { throw new Error("no body"); } });
    vi.stubGlobal("fetch", fetchMock);
    await expect(deleteGroup(7)).resolves.toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/groups/7");
    expect(init.method).toBe("DELETE");
  });

  it("buy 走 POST /positions/buy 并解析持仓", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => positionJson });
    vi.stubGlobal("fetch", fetchMock);
    const cmd = { groupId: 1, stockCode: "600519", stockName: "贵州茅台", tradeDate: "2026-08-27", price: 110, quantity: 100, fee: 0 };
    const pos = await buy(cmd);
    expect(pos.stockName).toBe("贵州茅台");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/positions/buy");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify(cmd));
  });

  it("sell 走 POST /positions/sell 并解析持仓", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => positionJson });
    vi.stubGlobal("fetch", fetchMock);
    const cmd = { positionId: 5, tradeDate: "2026-08-28", price: 120, quantity: 60, fee: 1 };
    const pos = await sell(cmd);
    expect(pos.id).toBe(5);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/positions/sell");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify(cmd));
  });

  it("addCashDividend 走 POST /positions/cash-dividend", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => positionJson });
    vi.stubGlobal("fetch", fetchMock);
    const cmd = { positionId: 5, exDate: "2026-06-30", cashPerShare: 2.5 };
    await addCashDividend(cmd);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/positions/cash-dividend");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify(cmd));
  });

  it("addStockDividend 走 POST /positions/stock-dividend", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => positionJson });
    vi.stubGlobal("fetch", fetchMock);
    const cmd = { positionId: 5, exDate: "2026-06-30", stockRatio: 0.1 };
    await addStockDividend(cmd);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/positions/stock-dividend");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify(cmd));
  });

  it("deletePosition 走 DELETE 并以 204 返回 undefined", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 204, json: async () => { throw new Error("no body"); } });
    vi.stubGlobal("fetch", fetchMock);
    await expect(deletePosition(5)).resolves.toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/portfolio/positions/5");
    expect(init.method).toBe("DELETE");
  });

  it("fetchAllocation 解析资产配置", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ slices: [{ category: "股票", marketValue: 7200, ratio: 0.6 }] }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const data = await fetchAllocation();
    expect(data.slices[0].category).toBe("股票");
    expect(data.slices[0].ratio).toBe(0.6);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/portfolio/allocation");
  });

  it("fetchIndustryDistribution 解析行业分布", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ slices: [{ industryName: "白酒", marketValue: 7200, ratio: 1 }] }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const data = await fetchIndustryDistribution();
    expect(data.slices[0].industryName).toBe("白酒");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/portfolio/industry-distribution");
  });

  it("fetchConcentration 解析持仓集中度", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({
        holdings: [{ stockCode: "600519", stockName: "贵州茅台", marketValue: 7200, ratio: 0.6 }],
        top5Ratio: 0.6,
      }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const data = await fetchConcentration();
    expect(data.top5Ratio).toBe(0.6);
    expect(data.holdings[0].stockCode).toBe("600519");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/portfolio/concentration");
  });

  it("非 2xx 且响应带 message 时抛出后端错误消息", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false, status: 400,
      json: async () => ({ message: "卖出数量超过持仓" }),
    }));
    await expect(sell({ positionId: 5, tradeDate: "2026-08-28", price: 120, quantity: 999, fee: 0 }))
      .rejects.toThrow("卖出数量超过持仓");
  });

  it("非 2xx 且响应无 message 字段时回退默认错误消息", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false, status: 500,
      json: async () => ({ error: "internal" }),
    }));
    await expect(fetchOverview()).rejects.toThrow("请求失败");
  });

  it("非 2xx 且响应体非 JSON 时回退默认错误消息", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: false, status: 502,
      json: async () => { throw new Error("not json"); },
    }));
    await expect(fetchGroups()).rejects.toThrow("请求失败");
  });

  it("响应不符合 zod schema 时抛校验错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => [{ id: "not-a-number" }],
    }));
    await expect(fetchGroups()).rejects.toThrow();
  });
});
