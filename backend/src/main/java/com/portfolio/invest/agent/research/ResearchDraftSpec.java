package com.portfolio.invest.agent.research;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.util.List;

/**
 * Agent 引导层草稿的结构化契约（四阶段变体的判别联合，与前端 lib/research-draft.ts 对齐）。
 * 显式携带 specVersion/stage 组件（勿依赖 Jackson 类名推断）；每个 record 标 @JsonInclude(NON_NULL)：
 * wire 契约默认 ALWAYS 会把 null 字段发出去，而前端 zod .optional() 不接受 null。
 * stage 取值与 ResearchStage 枚举一致：NEW_ANALYSIS/STRATEGY/POSITION/REVIEW。
 */
public sealed interface ResearchDraftSpec {
  int specVersion();
  String stage();

  /** 当前契约版本，工厂统一盖入 specVersion。 */
  int CURRENT_VERSION = 1;

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record AnalysisDraft(int specVersion, String stage, String symbol, String companyName,
                       String industry, List<String> checklistDone, String summary)
          implements ResearchDraftSpec {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record StrategyDraft(int specVersion, String stage, String thesis, BigDecimal valuationLow,
                       BigDecimal valuationHigh, String positionPlan, String buyConditions,
                       List<FalsifierItem> riskItems)
          implements ResearchDraftSpec {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record EntryPlanDraft(int specVersion, String stage, List<BatchItem> batches, BigDecimal winRate,
                        BigDecimal payoffRatio, String note)
          implements ResearchDraftSpec {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record ReviewDraft(int specVersion, String stage, String tier, String periodStart,
                     String periodEnd, String narrative)
          implements ResearchDraftSpec {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record FalsifierItem(String kind, String predicate, BigDecimal threshold, String note) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  record BatchItem(BigDecimal priceLow, BigDecimal priceHigh, long quantity, BigDecimal ratio) {}

  static ResearchDraftSpec analysis(String symbol, String companyName, String industry,
                                    List<String> checklistDone, String summary) {
    return new AnalysisDraft(CURRENT_VERSION, "NEW_ANALYSIS", symbol, companyName, industry,
            checklistDone, summary);
  }

  static ResearchDraftSpec strategy(BigDecimal valuationLow, BigDecimal valuationHigh, String thesis,
                                    String positionPlan, String buyConditions,
                                    List<FalsifierItem> riskItems) {
    return new StrategyDraft(CURRENT_VERSION, "STRATEGY", thesis, valuationLow, valuationHigh,
            positionPlan, buyConditions, riskItems);
  }

  static ResearchDraftSpec entryPlan(List<BatchItem> batches, BigDecimal winRate,
                                     BigDecimal payoffRatio, String note) {
    return new EntryPlanDraft(CURRENT_VERSION, "POSITION", batches, winRate, payoffRatio, note);
  }

  static ResearchDraftSpec review(String tier, String periodStart, String periodEnd,
                                  String narrative) {
    return new ReviewDraft(CURRENT_VERSION, "REVIEW", tier, periodStart, periodEnd, narrative);
  }
}
