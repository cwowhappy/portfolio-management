import { afterEach, describe, it, expect, vi } from "vitest";
import { activatePlan, ackRebalance, createPlan, fetchBacktest, fetchDeviation, fetchPlans, fetchRebalance, fetchTemplates, submitAssessment } from "@/lib/allocationApi";

const planJson = { id: 5, name: "平衡", source: "TEMPLATE", weights: [{ assetClass: "STOCK", weight: 60 }, { assetClass: "BOND", weight: 40 }], active: false, rebalanceFrequency: "OFF", lastRebalancedAt: null };

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

  it("fetchRebalance 解析再平衡视图；ackRebalance 走 POST 204", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({
        hasActivePlan: true, totalAssets: 10000, suppressed: false, anyAlert: true,
        items: [{ assetClass: "STOCK", targetWeight: 25, actualWeight: 0, deviation: -25,
          targetAmount: 2500, currentAmount: 0, suggestedAmount: 2500, thresholdBreached: true }],
        timeTrigger: null,
      }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const v = await fetchRebalance();
    expect(v.anyAlert).toBe(true);
    expect(v.items[0].suggestedAmount).toBe(2500);
    expect(fetchMock.mock.calls[0][0]).toBe("/api/allocation/rebalance");

    fetchMock.mockResolvedValueOnce({ ok: true, status: 204, json: async () => ({}), text: async () => "" });
    await ackRebalance();
    const [url, init] = fetchMock.mock.lastCall as [string, RequestInit];
    expect(url).toBe("/api/allocation/rebalance/ack");
    expect(init.method).toBe("POST");
  });

  it("响应不符合 schema 时抛校验错误", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue({ ok: true, status: 200, json: async () => ({ slices: [{ assetClass: "CRYPTO" }] }) }));
    await expect(fetchDeviation()).rejects.toThrow();
  });

  it("fetchBacktest：查询串含 planId/template/window/rebalance，View 数值按字符串契约解析", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true, status: 200,
      json: async () => ({
        planName: "永久组合", windowStart: "2021-09-24", windowEnd: "2026-09-24", window: "5Y", rebalance: "annual",
        curve: [{ date: "2021-09-24", value: "1000" }, { date: "2026-09-24", value: "1310.5" }],
        annualizedReturn: "0.0555", mdd: "0.12", sharpe: null, rfFallback: true,
      }),
    });
    vi.stubGlobal("fetch", fetchMock);
    const view = await fetchBacktest({ planId: 3, template: "all-weather", window: "5Y", rebalance: "annual" });
    expect(view.planName).toBe("永久组合");
    expect(view.curve[1]).toEqual({ date: "2026-09-24", value: "1310.5" });
    expect(view.sharpe).toBeNull();
    expect(view.rfFallback).toBe(true);
    const [url] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url.startsWith("/api/allocation/backtest?")).toBe(true);
    const qs = new URLSearchParams(url.split("?")[1]);
    expect(qs.get("planId")).toBe("3");
    expect(qs.get("template")).toBe("all-weather");
    expect(qs.get("window")).toBe("5Y");
    expect(qs.get("rebalance")).toBe("annual");
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
      json: async () => [{ id: 1, name: "测评推荐·成长", source: "ASSESSMENT", weights: [], active: false, rebalanceFrequency: "OFF", lastRebalancedAt: null }],
    });
    vi.stubGlobal("fetch", fetchMock);
    const plans = await fetchPlans();
    expect(plans[0]!.source).toBe("ASSESSMENT");
    expect(fetchMock.mock.calls[0][0]).toBe("/api/allocation/plans");
  });
});
