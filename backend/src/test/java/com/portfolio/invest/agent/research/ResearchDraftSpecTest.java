package com.portfolio.invest.agent.research;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Jackson 序列化形态钉住：与前端 lib/research-draft.ts（zod discriminatedUnion）一一对齐。 */
class ResearchDraftSpecTest {

    private static final ObjectMapper MAPPER = new ObjectMapper(); // 纯 record + 注解即可序列化，无需 Spring

    @DisplayName("序列化剥 null 字段且携带 specVersion/stage 判别组件")
    @Test
    void givenStrategyDraft_whenSerialized_thenNoNullFieldsAndDiscriminatorsPresent() throws Exception {
        String json = MAPPER.writeValueAsString(ResearchDraftSpec.strategy(
                new BigDecimal("12.5"), new BigDecimal("18.0"), "逻辑", null, "条件", List.of()));
        assertThat(json).contains("\"specVersion\":1").contains("\"stage\":\"STRATEGY\"");
        assertThat(json).doesNotContain("null");
    }

    @DisplayName("analysis 草稿：stage 为 NEW_ANALYSIS，null 字段整个省略")
    @Test
    void givenAnalysisDraftWithMissingIndustry_whenSerialized_thenStageNewAnalysisAndNullOmitted() throws Exception {
        String json = MAPPER.writeValueAsString(ResearchDraftSpec.analysis(
                "600519", "贵州茅台", null, List.of("商业模式已核", "ROE 趋势已看"), "白酒龙头，护城河清晰"));
        assertThat(json).contains("\"specVersion\":1").contains("\"stage\":\"NEW_ANALYSIS\"");
        assertThat(json).contains("\"symbol\":\"600519\"").contains("\"companyName\":\"贵州茅台\"");
        assertThat(json).contains("\"checklistDone\":[\"商业模式已核\",\"ROE 趋势已看\"]");
        assertThat(json).contains("\"summary\":\"白酒龙头，护城河清晰\"");
        assertThat(json).doesNotContain("\"industry\""); // NON_NULL：null 字段整个省略
        assertThat(json).doesNotContain("null");
    }

    @DisplayName("entryPlan 草稿：stage 为 POSITION，批次与胜率盈亏比序列化")
    @Test
    void givenEntryPlanDraft_whenSerialized_thenStagePositionAndBatchesPresent() throws Exception {
        String json = MAPPER.writeValueAsString(ResearchDraftSpec.entryPlan(
                List.of(new ResearchDraftSpec.BatchItem(
                        new BigDecimal("12.0"), new BigDecimal("12.5"), 1000L, new BigDecimal("0.4"))),
                new BigDecimal("0.6"), new BigDecimal("2.0"), "分两批建仓"));
        assertThat(json).contains("\"specVersion\":1").contains("\"stage\":\"POSITION\"");
        assertThat(json).contains("\"priceLow\":12.0").contains("\"priceHigh\":12.5");
        assertThat(json).contains("\"quantity\":1000").contains("\"ratio\":0.4");
        assertThat(json).contains("\"winRate\":0.6").contains("\"payoffRatio\":2.0");
        assertThat(json).contains("\"note\":\"分两批建仓\"");
        assertThat(json).doesNotContain("null");
    }

    @DisplayName("review 草稿：stage 为 REVIEW，档位与复盘区间序列化")
    @Test
    void givenReviewDraft_whenSerialized_thenStageReviewAndPeriodPresent() throws Exception {
        String json = MAPPER.writeValueAsString(ResearchDraftSpec.review(
                "A", "2026-01-01", "2026-09-30", "逻辑兑现，估值回归合理区间"));
        assertThat(json).contains("\"specVersion\":1").contains("\"stage\":\"REVIEW\"");
        assertThat(json).contains("\"tier\":\"A\"");
        assertThat(json).contains("\"periodStart\":\"2026-01-01\"").contains("\"periodEnd\":\"2026-09-30\"");
        assertThat(json).contains("\"narrative\":\"逻辑兑现，估值回归合理区间\"");
        assertThat(json).doesNotContain("null");
    }

    @DisplayName("CURRENT_VERSION 为 1，工厂盖的 specVersion 与之一致")
    @Test
    void givenFactories_whenStampingSpecVersion_thenEqualsCurrentVersion() {
        assertThat(ResearchDraftSpec.CURRENT_VERSION).isEqualTo(1);
        assertThat(ResearchDraftSpec.analysis("600519", "贵州茅台", "白酒", List.of(), "摘要").specVersion())
                .isEqualTo(ResearchDraftSpec.CURRENT_VERSION);
        assertThat(ResearchDraftSpec.strategy(new BigDecimal("12.5"), new BigDecimal("18.0"),
                "逻辑", null, "条件", List.of()).specVersion()).isEqualTo(ResearchDraftSpec.CURRENT_VERSION);
        assertThat(ResearchDraftSpec.entryPlan(List.of(), null, null, null).specVersion())
                .isEqualTo(ResearchDraftSpec.CURRENT_VERSION);
        assertThat(ResearchDraftSpec.review("A", null, null, "复盘").specVersion())
                .isEqualTo(ResearchDraftSpec.CURRENT_VERSION);
    }

    @DisplayName("四变体 stage 判别组件各归其位")
    @Test
    void givenFourVariants_whenReadingStage_thenEachMapsItsOwnStage() {
        assertThat(ResearchDraftSpec.analysis("600519", "贵州茅台", "白酒", List.of(), "摘要").stage())
                .isEqualTo("NEW_ANALYSIS");
        assertThat(ResearchDraftSpec.strategy(new BigDecimal("12.5"), new BigDecimal("18.0"),
                "逻辑", null, "条件", List.of()).stage()).isEqualTo("STRATEGY");
        assertThat(ResearchDraftSpec.entryPlan(List.of(), null, null, null).stage()).isEqualTo("POSITION");
        assertThat(ResearchDraftSpec.review("A", null, null, "复盘").stage()).isEqualTo("REVIEW");
    }
}
