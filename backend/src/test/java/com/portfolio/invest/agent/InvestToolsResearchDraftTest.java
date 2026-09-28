package com.portfolio.invest.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.portfolio.invest.application.industry.IndustryApplicationService;
import com.portfolio.invest.application.market.FinancialQueryService;
import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.application.screening.ScreeningApplicationService;
import com.portfolio.invest.application.valuation.ValuationApplicationService;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** research_draft 只读工具：四阶段草稿回显（摘要 + ```research-draft 围栏块）与参数错误兜底。 */
class InvestToolsResearchDraftTest {

    private static final String FENCE_START = "\n```research-draft\n";

    private ObjectMapper mapper;
    private InvestTools tools;

    @BeforeEach
    void setUp() {
        // 服务依赖本工具不触达（纯回显），mock 仅为满足构造器
        mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        tools = new InvestTools(
                mock(MarketDataService.class),
                mock(ValuationApplicationService.class),
                mock(ScreeningApplicationService.class),
                mock(FinancialQueryService.class),
                mock(IndustryApplicationService.class),
                mapper);
    }

    /** 从返回文本中切出围栏内的 spec JSON（剥掉摘要与围栏标记）。 */
    private JsonNode fencedSpec(String out) throws Exception {
        assertThat(out).endsWith("```");
        int from = out.indexOf(FENCE_START) + FENCE_START.length();
        return mapper.readTree(out.substring(from, out.length() - "```".length()));
    }

    @DisplayName("STRATEGY 草稿：回显摘要 + 围栏 spec，判别字段与证伪条目齐备")
    @Test
    void givenStrategyDraftJson_whenResearchDraft_thenEchoesSummaryAndFencedSpec() throws Exception {
        String draftJson = """
                {
                  "valuationLow": 12.5,
                  "valuationHigh": 18.0,
                  "thesis": "白酒龙头，护城河清晰",
                  "positionPlan": "首仓 4 成，回撤加仓",
                  "buyConditions": "估值分位低于 30%",
                  "riskItems": [
                    {"kind": "price", "predicate": "收盘跌破前低", "threshold": 11.0, "note": "止损离场"},
                    {"kind": "logic", "predicate": "批价持续下行", "note": "逻辑证伪"}
                  ]
                }
                """;

        String out = tools.researchDraft("STRATEGY", draftJson);

        // 摘要进 LLM：阶段标签 + 关键字段（证伪条数）
        assertThat(out).contains("策略草稿已回显").contains("估值区间 12.5~18.0").contains("证伪条件 2 条");
        // 围栏块出现在工具结果文本中（前端按 ```research-draft 标记提取）
        assertThat(out).contains(FENCE_START).endsWith("```");
        JsonNode spec = fencedSpec(out);
        assertThat(spec.get("specVersion").asInt()).isEqualTo(1);
        assertThat(spec.get("stage").asText()).isEqualTo("STRATEGY");
        assertThat(spec.get("thesis").asText()).isEqualTo("白酒龙头，护城河清晰");
        assertThat(spec.get("riskItems")).hasSize(2);
        assertThat(spec.get("riskItems").get(0).get("threshold").decimalValue())
                .isEqualByComparingTo(new BigDecimal("11.0"));
        assertThat(spec.get("riskItems").get(1).has("threshold")).isFalse(); // NON_NULL：null 字段省略
    }

    @DisplayName("NEW_ANALYSIS 草稿：stage 判别为 NEW_ANALYSIS，清单序列化")
    @Test
    void givenAnalysisDraftJson_whenResearchDraft_thenEchoesNewAnalysisSpec() throws Exception {
        String draftJson = """
                {
                  "symbol": "600519",
                  "companyName": "贵州茅台",
                  "industry": "白酒",
                  "checklistDone": ["商业模式已核", "ROE 趋势已看"],
                  "summary": "白酒龙头，格局稳定"
                }
                """;

        String out = tools.researchDraft("NEW_ANALYSIS", draftJson);

        assertThat(out).contains("分析草稿已回显").contains("600519").contains("清单 2 项");
        JsonNode spec = fencedSpec(out);
        assertThat(spec.get("stage").asText()).isEqualTo("NEW_ANALYSIS");
        assertThat(spec.get("symbol").asText()).isEqualTo("600519");
        assertThat(spec.get("checklistDone")).hasSize(2);
        assertThat(spec.get("summary").asText()).isEqualTo("白酒龙头，格局稳定");
    }

    @DisplayName("POSITION 草稿：stage 判别为 POSITION，批次/胜率/盈亏比序列化")
    @Test
    void givenEntryPlanDraftJson_whenResearchDraft_thenEchoesPositionSpec() throws Exception {
        String draftJson = """
                {
                  "batches": [
                    {"priceLow": 12.0, "priceHigh": 12.5, "quantity": 1000, "ratio": 0.4},
                    {"priceLow": 11.5, "priceHigh": 12.0, "quantity": 1500, "ratio": 0.6}
                  ],
                  "winRate": 0.6,
                  "payoffRatio": 2.0,
                  "note": "分两批建仓"
                }
                """;

        String out = tools.researchDraft("POSITION", draftJson);

        assertThat(out).contains("建仓草稿已回显").contains("2 批建仓").contains("胜率 0.6").contains("盈亏比 2.0");
        JsonNode spec = fencedSpec(out);
        assertThat(spec.get("stage").asText()).isEqualTo("POSITION");
        assertThat(spec.get("batches")).hasSize(2);
        assertThat(spec.get("batches").get(0).get("quantity").asLong()).isEqualTo(1000);
        assertThat(spec.get("batches").get(0).get("ratio").decimalValue())
                .isEqualByComparingTo(new BigDecimal("0.4"));
        assertThat(spec.get("note").asText()).isEqualTo("分两批建仓");
    }

    @DisplayName("REVIEW 草稿：stage 判别为 REVIEW，档位与复盘区间序列化")
    @Test
    void givenReviewDraftJson_whenResearchDraft_thenEchoesReviewSpec() throws Exception {
        String draftJson = """
                {
                  "tier": "A",
                  "periodStart": "2026-01-01",
                  "periodEnd": "2026-09-30",
                  "narrative": "逻辑兑现，估值回归合理区间"
                }
                """;

        String out = tools.researchDraft("REVIEW", draftJson);

        assertThat(out).contains("复盘草稿已回显").contains("档位 A").contains("2026-01-01~2026-09-30");
        JsonNode spec = fencedSpec(out);
        assertThat(spec.get("stage").asText()).isEqualTo("REVIEW");
        assertThat(spec.get("tier").asText()).isEqualTo("A");
        assertThat(spec.get("narrative").asText()).isEqualTo("逻辑兑现，估值回归合理区间");
    }

    @DisplayName("字段缺失容忍：只给 thesis 也能回显，null 字段整个省略")
    @Test
    void givenOnlyThesis_whenResearchDraft_thenNullFieldsToleratedAndOmitted() throws Exception {
        String out = tools.researchDraft("STRATEGY", "{\"thesis\":\"只有论点\"}");

        assertThat(out).contains(FENCE_START);
        JsonNode spec = fencedSpec(out);
        assertThat(spec.get("thesis").asText()).isEqualTo("只有论点");
        assertThat(spec.has("valuationLow")).isFalse();
        assertThat(spec.has("positionPlan")).isFalse();
        assertThat(spec.has("riskItems")).isFalse();
        assertThat(spec.toPrettyString()).doesNotContain("null");
    }

    @DisplayName("非法 stage：返回参数错误文本，不抛异常不带围栏")
    @Test
    void givenUnknownStage_whenResearchDraft_thenReturnsErrorTextWithoutThrowing() {
        String out = tools.researchDraft("STAGE_X", "{\"thesis\":\"x\"}");

        assertThat(out).startsWith("[research_draft] 参数错误").contains("stage");
        assertThat(out).contains("NEW_ANALYSIS").contains("STRATEGY").contains("POSITION").contains("REVIEW");
        assertThat(out).doesNotContain("```");
    }

    @DisplayName("stage 大小写与空白：trim 后精确匹配，小写值视为非法")
    @Test
    void givenLowercaseStage_whenResearchDraft_thenRejected() {
        String out = tools.researchDraft(" strategy ", "{\"thesis\":\"x\"}");

        assertThat(out).startsWith("[research_draft] 参数错误");
    }

    @DisplayName("非法 JSON：返回参数错误文本，不抛异常")
    @Test
    void givenMalformedJson_whenResearchDraft_thenReturnsErrorTextWithoutThrowing() {
        assertThat(tools.researchDraft("STRATEGY", "{oops"))
                .startsWith("[research_draft] 参数错误");
        assertThat(tools.researchDraft("REVIEW", "   "))
                .startsWith("[research_draft] 参数错误");
    }

    @DisplayName("非对象 JSON 根：返回参数错误文本")
    @Test
    void givenArrayRootJson_whenResearchDraft_thenReturnsErrorText() {
        assertThat(tools.researchDraft("POSITION", "[1,2]"))
                .startsWith("[research_draft] 参数错误").contains("对象");
    }

    @DisplayName("字段类型错：数字字段给非数值文本，返回参数错误并指认字段")
    @Test
    void givenTypeMismatchedField_whenResearchDraft_thenReturnsErrorTextNamingField() {
        String out = tools.researchDraft("POSITION", "{\"winRate\":\"高\"}");

        assertThat(out).startsWith("[research_draft] 参数错误").contains("winRate");
        assertThat(out).doesNotContain("```");
    }

    @DisplayName("字符串字段给数字：类型错返回参数错误文本")
    @Test
    void givenNumericValueForStringField_whenResearchDraft_thenReturnsErrorText() {
        assertThat(tools.researchDraft("REVIEW", "{\"tier\":1,\"narrative\":\"x\"}"))
                .startsWith("[research_draft] 参数错误").contains("tier");
    }
}
