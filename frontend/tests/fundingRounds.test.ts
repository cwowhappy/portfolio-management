import { describe, expect, it } from "vitest";
import { FUNDING_ROUNDS, roundLabel } from "@/lib/fundingRounds";

/**
 * FundingRound 镜像表锁定（P2 评审收口 #2）：fundingRounds.ts 是后端
 * domain/industry/FundingRound.java 枚举的同源镜像，双文件漂移此前无测试锁定
 * （注释曾失实指向组件测试）。本文件以全量 toEqual + 顺序断言钉死 15 项 value+label
 * 与声明序（= 后端 order()，全景卡分布排序依赖）。
 */
describe("FUNDING_ROUNDS 镜像锁定", () => {
  it("15 项 value+label 全量锁定（与后端 FundingRound 枚举逐一对应）", () => {
    expect(FUNDING_ROUNDS).toEqual([
      { value: "SEED", label: "种子轮" },
      { value: "ANGEL", label: "天使轮" },
      { value: "PRE_A", label: "Pre-A轮" },
      { value: "A", label: "A轮" },
      { value: "A_PLUS", label: "A+轮" },
      { value: "B", label: "B轮" },
      { value: "B_PLUS", label: "B+轮" },
      { value: "C", label: "C轮" },
      { value: "C_PLUS", label: "C+轮" },
      { value: "D", label: "D轮" },
      { value: "STRATEGIC", label: "战略投资" },
      { value: "PRE_IPO", label: "Pre-IPO轮" },
      { value: "IPO", label: "IPO" },
      { value: "ACQUIRED", label: "被收购" },
      { value: "UNKNOWN", label: "未知" },
    ]);
  });

  it("顺序断言：数组序 = 后端 order() 声明序（早→晚），逐对相邻比较", () => {
    const values = FUNDING_ROUNDS.map((r) => r.value);
    expect(values.indexOf("SEED")).toBe(0);
    expect(values.indexOf("UNKNOWN")).toBe(14);
    // 关键排序锚点：种子最早、IPO 前于 ACQUIRED/UNKNOWN（scatter 图 y 轴与分布排序依赖）
    expect(values.indexOf("SEED")).toBeLessThan(values.indexOf("A"));
    expect(values.indexOf("A")).toBeLessThan(values.indexOf("B"));
    expect(values.indexOf("D")).toBeLessThan(values.indexOf("STRATEGIC"));
    expect(values.indexOf("STRATEGIC")).toBeLessThan(values.indexOf("PRE_IPO"));
    expect(values.indexOf("IPO")).toBeLessThan(values.indexOf("ACQUIRED"));
  });

  it("roundLabel：命中取中文 label，未命中回退原值", () => {
    expect(roundLabel("B_PLUS")).toBe("B+轮");
    expect(roundLabel("STRATEGIC")).toBe("战略投资");
    expect(roundLabel("NOT_A_ROUND")).toBe("NOT_A_ROUND");
  });
});
