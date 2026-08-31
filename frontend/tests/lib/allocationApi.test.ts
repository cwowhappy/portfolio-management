import { afterEach, describe, it, expect, vi } from "vitest";
import { activatePlan, createPlan, fetchDeviation, fetchTemplates } from "@/lib/allocationApi";

const planJson = { id: 5, name: "平衡", source: "TEMPLATE", weights: [{ assetClass: "STOCK", weight: 60 }, { assetClass: "BOND", weight: 40 }], active: false };

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("allocationApi", () => {
  it("fetchTemplates 解析模板列表", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => [{ id: "BALANCED_60_40", name: "60/40 股债平衡", weights: [{ assetClass: "STOCK", weight: 60 }] }],
    });
    vi.stubGlobal("fetch", fetchMock);
    const data = await fetchTemplates();
    expect(data[0].name).toBe("60/40 股债平衡");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/allocation/templates");
  });

  it("createPlan 走 POST 并解析方案", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 201, json: async () => planJson });
    vi.stubGlobal("fetch", fetchMock);
    const cmd = { name: "平衡", source: "TEMPLATE" as const, weights: [{ assetClass: "STOCK" as const, weight: 60 }, { assetClass: "BOND" as const, weight: 40 }] };
    const plan = await createPlan(cmd);
    expect(plan.name).toBe("平衡");
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/allocation/plans");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify(cmd));
  });

  it("activatePlan 走 POST /activate", async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ ...planJson, active: true }) });
    vi.stubGlobal("fetch", fetchMock);
    const plan = await activatePlan(5);
    expect(plan.active).toBe(true);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/allocation/plans/5/activate");
    expect(init.method).toBe("POST");
  });

  it("fetchDeviation 解析偏离度", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({ slices: [{ assetClass: "STOCK", targetWeight: 60, actualWeight: 70.59, deviation: 10.59 }] }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const d = await fetchDeviation();
    expect(d.slices[0].assetClass).toBe("STOCK");
    expect(d.slices[0].deviation).toBe(10.59);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/allocation/deviation");
  });

  it("响应不符合 schema 时抛校验错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ slices: [{ assetClass: "CRYPTO" }] }) }));
    await expect(fetchDeviation()).rejects.toThrow();
  });
});
