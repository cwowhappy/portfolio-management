import { describe, it, expect } from "vitest";
import { ResearchDraftSchema, extractResearchDraftJson } from "@/lib/research-draft";

// 与后端 InvestToolsResearchDraftTest 的 wire 形态对齐（Task 2 契约）：
// NON_NULL 剥离保证 wire 上缺失≠null——可选字段一律 .optional() 不接受 null。
const analysisDraft = {
  specVersion: 1, stage: "NEW_ANALYSIS",
  symbol: "600519", companyName: "贵州茅台", industry: "白酒",
  checklistDone: ["商业模式已核", "ROE 趋势已看"],
  summary: "白酒龙头，格局稳定",
};
const strategyDraft = {
  specVersion: 1, stage: "STRATEGY",
  thesis: "高端白酒需求刚性",
  valuationLow: 12.5, valuationHigh: 18.0,
  positionPlan: "首仓 10%，跌破估值下限不加仓",
  buyConditions: "PE 低于 20 且放量",
  riskItems: [
    { kind: "PRICE_BREAK_BELOW", predicate: "跌破价格", threshold: 12.5, note: "跌破估值下限" },
    { kind: "EVENT", note: "食品安全事件" },
  ],
};
const entryPlanDraft = {
  specVersion: 1, stage: "POSITION",
  batches: [
    { priceLow: 12.5, priceHigh: 13.0, quantity: 100, ratio: 0.1 },
    { priceLow: 11.8, quantity: 200, ratio: 0.2 },
  ],
  winRate: 0.55, payoffRatio: 2.5,
  note: "分两批建仓",
};
const reviewDraft = {
  specVersion: 1, stage: "REVIEW",
  tier: "MONTHLY", periodStart: "2026-08-01", periodEnd: "2026-08-31",
  narrative: "逻辑未破坏，继续持有",
};

describe("ResearchDraftSchema（四变体判别联合，stage 判别）", () => {
  it("解析合法 NEW_ANALYSIS（全字段）", () => {
    const r = ResearchDraftSchema.safeParse(analysisDraft);
    expect(r.success).toBe(true);
  });

  it("解析合法 STRATEGY（含 riskItems 结构，元素字段可缺省）", () => {
    const r = ResearchDraftSchema.safeParse(strategyDraft);
    expect(r.success).toBe(true);
  });

  it("解析合法 POSITION（batches 批次结构）", () => {
    const r = ResearchDraftSchema.safeParse(entryPlanDraft);
    expect(r.success).toBe(true);
  });

  it("解析合法 REVIEW", () => {
    const r = ResearchDraftSchema.safeParse(reviewDraft);
    expect(r.success).toBe(true);
  });

  it("字段缺失容忍：只给 thesis 的 STRATEGY 也通过（后端 NON_NULL 剥离，缺失≠null）", () => {
    expect(ResearchDraftSchema.safeParse({ specVersion: 1, stage: "STRATEGY", thesis: "只有论点" }).success).toBe(true);
  });

  it("字段缺失容忍：全空草稿（draftJson={} 形态）也通过", () => {
    expect(ResearchDraftSchema.safeParse({ specVersion: 1, stage: "REVIEW" }).success).toBe(true);
  });

  it("null 字段拒绝（wire 契约保证无 null）：字符串/数字/数组各一", () => {
    expect(ResearchDraftSchema.safeParse({ ...strategyDraft, thesis: null }).success).toBe(false);
    expect(ResearchDraftSchema.safeParse({ ...strategyDraft, valuationLow: null }).success).toBe(false);
    expect(ResearchDraftSchema.safeParse({ ...strategyDraft, riskItems: null }).success).toBe(false);
    expect(ResearchDraftSchema.safeParse({ ...analysisDraft, checklistDone: null }).success).toBe(false);
    expect(ResearchDraftSchema.safeParse({ ...reviewDraft, narrative: null }).success).toBe(false);
  });

  it("null 拒绝下沉到嵌套元素：riskItems[0].threshold / batches[0].ratio", () => {
    expect(ResearchDraftSchema.safeParse({
      ...strategyDraft, riskItems: [{ kind: "K", threshold: null }],
    }).success).toBe(false);
    expect(ResearchDraftSchema.safeParse({
      ...entryPlanDraft, batches: [{ priceLow: 12, quantity: 100, ratio: null }],
    }).success).toBe(false);
  });

  it("未知 specVersion 拒绝（z.literal(1) 锁版本，降级卡兜底）", () => {
    expect(ResearchDraftSchema.safeParse({ ...strategyDraft, specVersion: 2 }).success).toBe(false);
    expect(ResearchDraftSchema.safeParse({ ...reviewDraft, specVersion: "1" }).success).toBe(false);
  });

  it("未知 stage 拒绝（判别字段无匹配变体）", () => {
    expect(ResearchDraftSchema.safeParse({ specVersion: 1, stage: "STAGE_X", thesis: "x" }).success).toBe(false);
  });

  it("quantity 为批次必填字段（后端缺省默认 0，wire 恒在）", () => {
    expect(ResearchDraftSchema.safeParse({
      specVersion: 1, stage: "POSITION",
      batches: [{ priceLow: 12.5, priceHigh: 13.0, ratio: 0.1 }],
    }).success).toBe(false);
  });

  it("类型错拒绝：数量给字符串、清单元素给数字", () => {
    expect(ResearchDraftSchema.safeParse({
      ...entryPlanDraft, batches: [{ priceLow: 12.5, quantity: "100" }],
    }).success).toBe(false);
    expect(ResearchDraftSchema.safeParse({ ...analysisDraft, checklistDone: [1] }).success).toBe(false);
  });
});

describe("extractResearchDraftJson（工具结果文本围栏提取）", () => {
  const fenced = "```research-draft\n" + JSON.stringify(strategyDraft) + "\n```";

  it("完整工具结果（摘要 + 围栏）提取围栏内 JSON", () => {
    const result = "策略草稿已回显：估值区间 12.5~18.0，证伪条件 2 条\n" + fenced;
    expect(extractResearchDraftJson(result)).toBe(JSON.stringify(strategyDraft));
  });

  it("裸围栏（无摘要前缀）也可提取", () => {
    expect(extractResearchDraftJson(fenced)).toBe(JSON.stringify(strategyDraft));
  });

  it("无围栏（参数错误文本）返回 null", () => {
    expect(extractResearchDraftJson("[research_draft] 参数错误：未知 stage: STAGE_X")).toBeNull();
  });

  it("提取产物直接过 ResearchDraftSchema（提取 + 解析全链路）", () => {
    const result = "建仓草稿已回显：2 批建仓，胜率 0.55\n" +
      "```research-draft\n" + JSON.stringify(entryPlanDraft) + "\n```";
    const json = extractResearchDraftJson(result)!;
    const r = ResearchDraftSchema.safeParse(JSON.parse(json));
    expect(r.success).toBe(true);
  });
});
