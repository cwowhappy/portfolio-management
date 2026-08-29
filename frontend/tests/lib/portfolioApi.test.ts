import { describe, it, expect, vi } from "vitest";
import { fetchOverview, fetchGroups } from "@/lib/portfolioApi";

describe("portfolioApi", () => {
  it("fetchOverview 解析总览", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ totalAssets: 12000, totalCost: 10000, totalPnl: 2000, todayPnl: 0, cashTotal: 0, positionCount: 1, groupCount: 1 }),
    }));
    const data = await fetchOverview();
    expect(data.totalAssets).toBe(12000);
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
});
