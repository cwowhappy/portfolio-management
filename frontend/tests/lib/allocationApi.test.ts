import { afterEach, describe, it, expect, vi } from "vitest";
import { activatePlan, createPlan, fetchDeviation, fetchPlans, fetchTemplates, submitAssessment } from "@/lib/allocationApi";

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

describe("assessment api", () => {
  it("submitAssessment POST 答卷并按 schema 解析 AssessmentView", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({
        totalScore: 35, profile: "GROWTH", profileName: "成长",
        weights: [{ assetClass: "STOCK", weight: 65 }],
        answers: { Q1: "A" }, assessedAt: "2026-09-15T00:00:00Z",
      }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const view = await submitAssessment([{ questionId: "Q1", optionId: "A" }]);
    expect(view.profileName).toBe("成长");
    expect(view.totalScore).toBe(35);
    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe("/api/allocation/assessment");
    expect(init.method).toBe("POST");
    expect(init.body).toBe(JSON.stringify({ answers: [{ questionId: "Q1", optionId: "A" }] }));
  });

  it("PlanSourceSchema 接受 ASSESSMENT", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => [{ id: 1, name: "测评推荐·成长", source: "ASSESSMENT", weights: [], active: false }],
    });
    vi.stubGlobal("fetch", fetchMock);
    const plans = await fetchPlans();
    expect(plans[0]!.source).toBe("ASSESSMENT");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/allocation/plans");
  });
});
