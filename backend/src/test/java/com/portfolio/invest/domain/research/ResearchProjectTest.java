package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResearchProjectTest {

    private static final Instant CREATED = Instant.parse("2026-09-28T08:00:00Z");

    private static ResearchProject project() {
        return ResearchProject.create(1L, "600519", "贵州茅台", "BK0477", "茅台消费降级研究", ResearchStage.NEW_ANALYSIS);
    }

    @DisplayName("立项携带全部字段且默认 ACTIVE")
    @Test
    void givenValidInput_whenCreate_thenActiveProject() {
        var p = project();
        assertThat(p.id()).isNull();
        assertThat(p.userId()).isEqualTo(1L);
        assertThat(p.stockCode()).isEqualTo("600519");
        assertThat(p.stockName()).isEqualTo("贵州茅台");
        assertThat(p.industryCode()).isEqualTo("BK0477");
        assertThat(p.title()).isEqualTo("茅台消费降级研究");
        assertThat(p.currentStage()).isEqualTo(ResearchStage.NEW_ANALYSIS);
        assertThat(p.status()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(p.createdAt()).isNotNull();
        assertThat(p.updatedAt()).isNotNull();
        assertThat(p.version()).isNull();
    }

    @DisplayName("立项情报提醒默认开（决策 #26：项目开关默认 TRUE）")
    @Test
    void givenCreate_thenIntelligenceAlertDefaultsTrue() {
        assertThat(project().intelligenceAlertEnabled()).isTrue();
    }

    @DisplayName("withIntelligenceAlert 返回新实例且原实例不变（不可变聚合）")
    @Test
    void givenEnabledProject_whenWithIntelligenceAlertFalse_thenNewInstanceAndOriginalUntouched() {
        var p = project();
        var disabled = p.withIntelligenceAlert(false);
        assertThat(disabled.intelligenceAlertEnabled()).isFalse();
        // 不可变：原实例保持开，其余字段原样携带（照 changeStage 先例）
        assertThat(p.intelligenceAlertEnabled()).isTrue();
        assertThat(disabled.id()).isEqualTo(p.id());
        assertThat(disabled.title()).isEqualTo(p.title());
        assertThat(disabled.currentStage()).isEqualTo(p.currentStage());
        assertThat(disabled.status()).isEqualTo(p.status());
        // 再切回开，双向往返
        assertThat(disabled.withIntelligenceAlert(true).intelligenceAlertEnabled()).isTrue();
    }

    @DisplayName("withIntelligenceAlert 同值幂等返回原实例（照 changeStage 同值语义）")
    @Test
    void givenSameValue_whenWithIntelligenceAlert_thenSameInstance() {
        var p = project();
        assertThat(p.withIntelligenceAlert(true)).isSameAs(p);
        var disabled = p.withIntelligenceAlert(false);
        assertThat(disabled.withIntelligenceAlert(false)).isSameAs(disabled);
    }

    @DisplayName("既有 wither（archive/changeStage/rename）携带情报开关字段")
    @Test
    void givenDisabledProject_whenOtherWithers_thenFlagCarriedThrough() {
        var p = project().withIntelligenceAlert(false);
        assertThat(p.archive().intelligenceAlertEnabled()).isFalse();
        assertThat(p.changeStage(ResearchStage.REVIEW).intelligenceAlertEnabled()).isFalse();
        assertThat(p.rename("新标题").intelligenceAlertEnabled()).isFalse();
    }

    @DisplayName("行业代码可空（立项不强制行业）")
    @Test
    void givenNullIndustry_whenCreate_thenSucceed() {
        var p = ResearchProject.create(1L, "600519", "贵州茅台", null, "无行业研究", ResearchStage.REVIEW);
        assertThat(p.industryCode()).isNull();
    }

    @DisplayName("标的代码空白抛CODE_BLANK")
    @Test
    void givenBlankStockCode_whenCreate_thenThrowCodeBlank() {
        assertThatThrownBy(() -> ResearchProject.create(1L, "  ", "贵州茅台", null, "标题", ResearchStage.STRATEGY))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CODE_BLANK));
    }

    @DisplayName("标的代码缺失抛CODE_BLANK")
    @Test
    void givenNullStockCode_whenCreate_thenThrowCodeBlank() {
        assertThatThrownBy(() -> ResearchProject.create(1L, null, "贵州茅台", null, "标题", ResearchStage.STRATEGY))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.CODE_BLANK));
    }

    @DisplayName("标的名称空白抛NAME_BLANK")
    @Test
    void givenBlankStockName_whenCreate_thenThrowNameBlank() {
        assertThatThrownBy(() -> ResearchProject.create(1L, "600519", "", null, "标题", ResearchStage.STRATEGY))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NAME_BLANK));
    }

    @DisplayName("标题空白抛TITLE_BLANK")
    @Test
    void givenBlankTitle_whenCreate_thenThrowTitleBlank() {
        assertThatThrownBy(() -> ResearchProject.create(1L, "600519", "贵州茅台", null, "  ", ResearchStage.STRATEGY))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TITLE_BLANK));
    }

    @DisplayName("初始阶段缺失抛STAGE_REQUIRED")
    @Test
    void givenNullStage_whenCreate_thenThrowStageRequired() {
        assertThatThrownBy(() -> ResearchProject.create(1L, "600519", "贵州茅台", null, "标题", null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STAGE_REQUIRED));
    }

    @DisplayName("归属用户缺失抛USER_REQUIRED")
    @Test
    void givenNullUser_whenCreate_thenThrowUserRequired() {
        assertThatThrownBy(() -> ResearchProject.create(null, "600519", "贵州茅台", null, "标题", ResearchStage.STRATEGY))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.USER_REQUIRED));
    }

    @DisplayName("归档置ARCHIVED且返回新实例（原实例不变）")
    @Test
    void givenActiveProject_whenArchive_thenArchivedNewInstance() {
        var p = project();
        var archived = p.archive();
        assertThat(archived.status()).isEqualTo(ProjectStatus.ARCHIVED);
        // 不可变聚合：原实例保持 ACTIVE，其余字段原样携带
        assertThat(p.status()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(archived.id()).isEqualTo(p.id());
        assertThat(archived.title()).isEqualTo(p.title());
        assertThat(archived.currentStage()).isEqualTo(p.currentStage());
    }

    @DisplayName("changeStage 支持任意跳转（D4：NEW_ANALYSIS 直达 REVIEW）")
    @Test
    void givenNewAnalysis_whenChangeStageToReview_thenJumpDirectly() {
        var p = project().changeStage(ResearchStage.REVIEW);
        assertThat(p.currentStage()).isEqualTo(ResearchStage.REVIEW);
        assertThat(p.status()).isEqualTo(ProjectStatus.ACTIVE);
    }

    @DisplayName("changeStage 支持回退（D4：REVIEW 退回 NEW_ANALYSIS）")
    @Test
    void givenReviewStage_whenChangeStageBack_thenJumpBackward() {
        var p = project().changeStage(ResearchStage.REVIEW).changeStage(ResearchStage.NEW_ANALYSIS);
        assertThat(p.currentStage()).isEqualTo(ResearchStage.NEW_ANALYSIS);
    }

    @DisplayName("changeStage 缺失抛STAGE_REQUIRED")
    @Test
    void givenNullStage_whenChangeStage_thenThrowStageRequired() {
        assertThatThrownBy(() -> project().changeStage(null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STAGE_REQUIRED));
    }

    @DisplayName("rename 更新标题并返回新实例")
    @Test
    void givenNewTitle_whenRename_thenTitleUpdated() {
        var p = project().rename("茅台新标题");
        assertThat(p.title()).isEqualTo("茅台新标题");
    }

    @DisplayName("rename 空白标题抛TITLE_BLANK")
    @Test
    void givenBlankTitle_whenRename_thenThrowTitleBlank() {
        assertThatThrownBy(() -> project().rename(null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.TITLE_BLANK));
    }

    @DisplayName("reconstitute 全参还原持久化状态")
    @Test
    void givenAllFields_whenReconstitute_thenCarriedThrough() {
        var p = ResearchProject.reconstitute(9L, 1L, "600519", "贵州茅台", "BK0477", "标题",
                ResearchStage.POSITION, ProjectStatus.ARCHIVED, false, 3L, CREATED, CREATED.plusSeconds(60));
        assertThat(p.id()).isEqualTo(9L);
        assertThat(p.userId()).isEqualTo(1L);
        assertThat(p.stockCode()).isEqualTo("600519");
        assertThat(p.stockName()).isEqualTo("贵州茅台");
        assertThat(p.industryCode()).isEqualTo("BK0477");
        assertThat(p.title()).isEqualTo("标题");
        assertThat(p.currentStage()).isEqualTo(ResearchStage.POSITION);
        assertThat(p.status()).isEqualTo(ProjectStatus.ARCHIVED);
        assertThat(p.intelligenceAlertEnabled()).isFalse();
        assertThat(p.version()).isEqualTo(3L);
        assertThat(p.createdAt()).isEqualTo(CREATED);
        assertThat(p.updatedAt()).isEqualTo(CREATED.plusSeconds(60));
    }
}
