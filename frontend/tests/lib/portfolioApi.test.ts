import { afterEach, describe, it, expect, vi } from "vitest";
import {
  addCashTransaction, editTrade, fetchCashTransactions, fetchGroups, fetchOverview, fetchTrades, renameGroup,
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

  it("fetchCashTransactions 解析流水列表", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => [{ id: 9, groupId: 1, type: "WITHDRAW", amount: 5000, txDate: "2026-08-28", note: "转出" }],
    }));
    const txs = await fetchCashTransactions(1);
    expect(txs[0].type).toBe("WITHDRAW");
    expect(txs[0].amount).toBe(5000);
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
});
