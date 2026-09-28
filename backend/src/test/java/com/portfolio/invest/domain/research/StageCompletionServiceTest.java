package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S6/D16 完成度唯一计算点：优先级 REOPENED > 手动 COMPLETED > AUTO 齐套 > IN_PROGRESS（当前阶段）> NOT_STARTED。 */
class StageCompletionServiceTest {

    private static ResearchProject projectAt(ResearchStage stage) {
        return ResearchProject.create(1L, "600519", "贵州茅台", null, "茅台研究", stage);
    }

    /** 四阶段产物齐套（POSITION 需 2 件，其余各 1 件）。 */
    private static Map<ResearchStage, Integer> fullArtifacts() {
        return Map.of(
                ResearchStage.NEW_ANALYSIS, 1,
                ResearchStage.STRATEGY, 1,
                ResearchStage.POSITION, 2,
                ResearchStage.REVIEW, 1);
    }

    @DisplayName("产物齐套且无手动覆盖时四阶段全 AUTO 完成")
    @Test
    void givenFullArtifactsAndNoManual_whenCompute_thenAllAutoCompleted() {
        var result = StageCompletionService.compute(projectAt(ResearchStage.REVIEW), Map.of(), fullArtifacts());
        assertThat(result).containsOnlyKeys(ResearchStage.values());
        assertThat(result.get(ResearchStage.NEW_ANALYSIS))
                .isEqualTo(new StageCompletion(ResearchStage.NEW_ANALYSIS, StageStatus.COMPLETED, CompletionBasis.AUTO));
        assertThat(result.get(ResearchStage.STRATEGY))
                .isEqualTo(new StageCompletion(ResearchStage.STRATEGY, StageStatus.COMPLETED, CompletionBasis.AUTO));
        assertThat(result.get(ResearchStage.POSITION))
                .isEqualTo(new StageCompletion(ResearchStage.POSITION, StageStatus.COMPLETED, CompletionBasis.AUTO));
        assertThat(result.get(ResearchStage.REVIEW))
                .isEqualTo(new StageCompletion(ResearchStage.REVIEW, StageStatus.COMPLETED, CompletionBasis.AUTO));
    }

    @DisplayName("REOPENED 覆盖 AUTO：产物齐套仍算进行中")
    @Test
    void givenReopenedWithFullArtifacts_whenCompute_thenInProgressPending() {
        var result = StageCompletionService.compute(projectAt(ResearchStage.NEW_ANALYSIS),
                Map.of(ResearchStage.STRATEGY, StageCompletionService.ManualState.REOPENED),
                fullArtifacts());
        assertThat(result.get(ResearchStage.STRATEGY))
                .isEqualTo(new StageCompletion(ResearchStage.STRATEGY, StageStatus.IN_PROGRESS, CompletionBasis.PENDING));
    }

    @DisplayName("手动 COMPLETED 覆盖 AUTO：产物齐套时完成依据仍为 MANUAL")
    @Test
    void givenManualCompletedWithFullArtifacts_whenCompute_thenBasisManual() {
        var result = StageCompletionService.compute(projectAt(ResearchStage.REVIEW),
                Map.of(ResearchStage.REVIEW, StageCompletionService.ManualState.COMPLETED),
                fullArtifacts());
        assertThat(result.get(ResearchStage.REVIEW))
                .isEqualTo(new StageCompletion(ResearchStage.REVIEW, StageStatus.COMPLETED, CompletionBasis.MANUAL));
    }

    @DisplayName("手动 COMPLETED 无产物也完成（D16 兜底）")
    @Test
    void givenManualCompletedWithoutArtifacts_whenCompute_thenCompletedManual() {
        var result = StageCompletionService.compute(projectAt(ResearchStage.STRATEGY),
                Map.of(ResearchStage.POSITION, StageCompletionService.ManualState.COMPLETED),
                Map.of());
        assertThat(result.get(ResearchStage.POSITION))
                .isEqualTo(new StageCompletion(ResearchStage.POSITION, StageStatus.COMPLETED, CompletionBasis.MANUAL));
    }

    @DisplayName("产物不足且为当前阶段算进行中")
    @Test
    void givenInsufficientArtifactsAtCurrentStage_whenCompute_thenInProgress() {
        var result = StageCompletionService.compute(projectAt(ResearchStage.POSITION),
                Map.of(),
                Map.of(ResearchStage.POSITION, 1));
        assertThat(result.get(ResearchStage.POSITION))
                .isEqualTo(new StageCompletion(ResearchStage.POSITION, StageStatus.IN_PROGRESS, CompletionBasis.PENDING));
    }

    @DisplayName("无产物且非当前阶段算未开始")
    @Test
    void givenNoArtifactsNotCurrentStage_whenCompute_thenNotStarted() {
        var result = StageCompletionService.compute(projectAt(ResearchStage.NEW_ANALYSIS), Map.of(), Map.of());
        assertThat(result.get(ResearchStage.REVIEW))
                .isEqualTo(new StageCompletion(ResearchStage.REVIEW, StageStatus.NOT_STARTED, CompletionBasis.PENDING));
    }

    @DisplayName("ManualState.NULL 视同无覆盖落回 AUTO")
    @Test
    void givenNullManualState_whenCompute_thenFallThroughToAuto() {
        var result = StageCompletionService.compute(projectAt(ResearchStage.NEW_ANALYSIS),
                Map.of(ResearchStage.NEW_ANALYSIS, StageCompletionService.ManualState.NULL),
                Map.of(ResearchStage.NEW_ANALYSIS, 1));
        assertThat(result.get(ResearchStage.NEW_ANALYSIS))
                .isEqualTo(new StageCompletion(ResearchStage.NEW_ANALYSIS, StageStatus.COMPLETED, CompletionBasis.AUTO));
    }

    @DisplayName("POSITION 必需 2 件：1 件不齐套")
    @Test
    void givenPositionOneArtifact_whenCompute_thenNotCompleted() {
        var result = StageCompletionService.compute(projectAt(ResearchStage.NEW_ANALYSIS),
                Map.of(),
                Map.of(ResearchStage.POSITION, 1));
        assertThat(result.get(ResearchStage.POSITION).status()).isEqualTo(StageStatus.NOT_STARTED);
    }

    @DisplayName("目录静态映射：NEW_ANALYSIS/STRATEGY/REVIEW 各 1，POSITION 2")
    @Test
    void givenCatalog_whenRequired_thenStaticMapping() {
        assertThat(StageArtifactCatalog.required(ResearchStage.NEW_ANALYSIS)).isEqualTo(1);
        assertThat(StageArtifactCatalog.required(ResearchStage.STRATEGY)).isEqualTo(1);
        assertThat(StageArtifactCatalog.required(ResearchStage.POSITION)).isEqualTo(2);
        assertThat(StageArtifactCatalog.required(ResearchStage.REVIEW)).isEqualTo(1);
    }

    @DisplayName("阶段缺失时目录抛STAGE_REQUIRED")
    @Test
    void givenNullStage_whenRequired_thenThrowStageRequired() {
        assertThatThrownBy(() -> StageArtifactCatalog.required(null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STAGE_REQUIRED));
    }
}
