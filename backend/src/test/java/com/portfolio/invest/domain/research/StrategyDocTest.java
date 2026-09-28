package com.portfolio.invest.domain.research;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StrategyDocTest {

    private static final Instant CREATED = Instant.parse("2026-09-28T08:00:00Z");

    // ---- StrategyDoc：草稿 ----

    @DisplayName("draftOf 新建 DRAFT 且内容字段全空")
    @Test
    void givenProjectId_whenDraftOf_thenNewDraft() {
        var doc = StrategyDoc.draftOf(1L);
        assertThat(doc.id()).isNull();
        assertThat(doc.projectId()).isEqualTo(1L);
        assertThat(doc.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(doc.thesis()).isNull();
        assertThat(doc.valuationLow()).isNull();
        assertThat(doc.valuationHigh()).isNull();
        assertThat(doc.positionPlan()).isNull();
        assertThat(doc.buyConditions()).isNull();
        assertThat(doc.riskNotes()).isNull();
        assertThat(doc.finalizedAt()).isNull();
        assertThat(doc.version()).isNull();
        assertThat(doc.createdAt()).isNotNull();
        assertThat(doc.updatedAt()).isNotNull();
    }

    @DisplayName("draftOf 缺项目抛PROJECT_REQUIRED")
    @Test
    void givenNullProjectId_whenDraftOf_thenThrowProjectRequired() {
        assertThatThrownBy(() -> StrategyDoc.draftOf(null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.PROJECT_REQUIRED));
    }

    @DisplayName("saveDraft 暂存六字段且保持 DRAFT")
    @Test
    void givenDraft_whenSaveDraft_thenFieldsStored() {
        var doc = StrategyDoc.draftOf(1L)
                .saveDraft("消费降级下茅台量价背离", new BigDecimal("1200.00"), new BigDecimal("1500.00"),
                        "首仓 10%，回撤 8% 加至 20%", "日线缩量企稳 + 估值低于 1300", "政策风险、批价下行");
        assertThat(doc.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(doc.thesis()).isEqualTo("消费降级下茅台量价背离");
        assertThat(doc.valuationLow()).isEqualByComparingTo("1200");
        assertThat(doc.valuationHigh()).isEqualByComparingTo("1500");
        assertThat(doc.positionPlan()).isEqualTo("首仓 10%，回撤 8% 加至 20%");
        assertThat(doc.buyConditions()).isEqualTo("日线缩量企稳 + 估值低于 1300");
        assertThat(doc.riskNotes()).isEqualTo("政策风险、批价下行");
        assertThat(doc.finalizedAt()).isNull();
    }

    @DisplayName("saveDraft 全空字段可存（DRAFT 态字段可空）")
    @Test
    void givenAllNullFields_whenSaveDraft_thenSucceed() {
        var doc = StrategyDoc.draftOf(1L).saveDraft(null, null, null, null, null, null);
        assertThat(doc.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(doc.thesis()).isNull();
        assertThat(doc.valuationLow()).isNull();
        assertThat(doc.valuationHigh()).isNull();
    }

    @DisplayName("FINALIZED 态 saveDraft 抛STRATEGY_FINALIZED")
    @Test
    void givenFinalized_whenSaveDraft_thenThrowStrategyFinalized() {
        var finalized = finalizedDoc();
        assertThatThrownBy(() -> finalized.saveDraft("改逻辑", new BigDecimal("10"), new BigDecimal("20"),
                null, null, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STRATEGY_FINALIZED));
    }

    // ---- StrategyDoc：定稿 ----

    @DisplayName("下限小于上限 finalizeDoc 置FINALIZED 并盖 finalizedAt")
    @Test
    void givenValidRange_whenFinalizeDoc_thenFinalizedWithTimestamp() {
        var doc = StrategyDoc.draftOf(1L)
                .saveDraft("逻辑", new BigDecimal("1200.0"), new BigDecimal("1500.000"),
                        "仓位", "条件", "风险");
        var finalized = doc.finalizeDoc();
        assertThat(finalized.state()).isEqualTo(StrategyState.FINALIZED);
        assertThat(finalized.finalizedAt()).isNotNull();
        // 不可变聚合：原 DRAFT 实例不被改动
        assertThat(doc.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(doc.finalizedAt()).isNull();
        assertThat(finalized.thesis()).isEqualTo("逻辑");
        assertThat(finalized.valuationLow()).isEqualByComparingTo("1200");
    }

    @DisplayName("下限缺失 finalizeDoc 抛VALUATION_RANGE_INVALID")
    @Test
    void givenNullLow_whenFinalizeDoc_thenThrowValuationRangeInvalid() {
        var doc = StrategyDoc.draftOf(1L)
                .saveDraft("逻辑", null, new BigDecimal("1500"), null, null, null);
        assertThatThrownBy(doc::finalizeDoc)
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.VALUATION_RANGE_INVALID));
    }

    @DisplayName("上限缺失 finalizeDoc 抛VALUATION_RANGE_INVALID")
    @Test
    void givenNullHigh_whenFinalizeDoc_thenThrowValuationRangeInvalid() {
        var doc = StrategyDoc.draftOf(1L)
                .saveDraft("逻辑", new BigDecimal("1200"), null, null, null, null);
        assertThatThrownBy(doc::finalizeDoc)
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.VALUATION_RANGE_INVALID));
    }

    @DisplayName("下限等于上限 finalizeDoc 抛VALUATION_RANGE_INVALID（compareTo 判等，不看 scale）")
    @Test
    void givenLowEqualsHigh_whenFinalizeDoc_thenThrowValuationRangeInvalid() {
        var doc = StrategyDoc.draftOf(1L)
                .saveDraft("逻辑", new BigDecimal("1300.5"), new BigDecimal("1300.50"), null, null, null);
        assertThatThrownBy(doc::finalizeDoc)
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.VALUATION_RANGE_INVALID));
    }

    @DisplayName("下限大于上限 finalizeDoc 抛VALUATION_RANGE_INVALID")
    @Test
    void givenLowAboveHigh_whenFinalizeDoc_thenThrowValuationRangeInvalid() {
        var doc = StrategyDoc.draftOf(1L)
                .saveDraft("逻辑", new BigDecimal("1500"), new BigDecimal("1200"), null, null, null);
        assertThatThrownBy(doc::finalizeDoc)
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.VALUATION_RANGE_INVALID));
    }

    @DisplayName("已定稿再 finalizeDoc 幂等返回原实例")
    @Test
    void givenFinalized_whenFinalizeDocAgain_thenIdempotent() {
        var finalized = finalizedDoc();
        assertThat(finalized.finalizeDoc()).isSameAs(finalized);
    }

    // ---- StrategyDoc：修订（D13 覆盖式） ----

    @DisplayName("revise 回 DRAFT：清 finalizedAt、内容字段保留待覆盖")
    @Test
    void givenFinalized_whenRevise_thenBackToDraftKeepFields() {
        var revised = finalizedDoc().revise();
        assertThat(revised.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(revised.finalizedAt()).isNull();
        assertThat(revised.thesis()).isEqualTo("旧定稿逻辑");
        assertThat(revised.valuationLow()).isEqualByComparingTo("1200");
        assertThat(revised.valuationHigh()).isEqualByComparingTo("1500");
    }

    @DisplayName("revise 后可再 saveDraft：旧定稿字段被新草稿覆盖")
    @Test
    void givenRevise_whenSaveDraft_thenOldFinalizedFieldsOverwritten() {
        var finalized = finalizedDoc();
        var newDraft = finalized.revise()
                .saveDraft("新逻辑", new BigDecimal("1000"), new BigDecimal("1100"),
                        "新仓位", null, null);
        assertThat(newDraft.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(newDraft.thesis()).isEqualTo("新逻辑");
        assertThat(newDraft.valuationLow()).isEqualByComparingTo("1000");
        assertThat(newDraft.positionPlan()).isEqualTo("新仓位");
        // 覆盖式：未提供的旧字段不残留（buyConditions/riskNotes 被置空，不留版本链）
        assertThat(newDraft.buyConditions()).isNull();
        assertThat(newDraft.riskNotes()).isNull();
        // 不可变聚合：定稿中间实例不被改动
        assertThat(finalized.state()).isEqualTo(StrategyState.FINALIZED);
        assertThat(finalized.thesis()).isEqualTo("旧定稿逻辑");
        assertThat(finalized.buyConditions()).isEqualTo("旧买入条件");
    }

    @DisplayName("DRAFT 态 revise 幂等返回原实例")
    @Test
    void givenDraft_whenRevise_thenIdempotent() {
        var draft = StrategyDoc.draftOf(1L);
        assertThat(draft.revise()).isSameAs(draft);
    }

    @DisplayName("revise 后可重新定稿")
    @Test
    void givenReviseAndSave_whenFinalizeDoc_thenFinalizedAgain() {
        var doc = finalizedDoc().revise()
                .saveDraft("新逻辑", new BigDecimal("900"), new BigDecimal("1000"), null, null, null)
                .finalizeDoc();
        assertThat(doc.state()).isEqualTo(StrategyState.FINALIZED);
        assertThat(doc.finalizedAt()).isNotNull();
        assertThat(doc.valuationLow()).isEqualByComparingTo("900");
    }

    @DisplayName("reconstitute 全参还原持久化状态")
    @Test
    void givenAllFields_whenReconstitute_thenCarriedThrough() {
        var doc = StrategyDoc.reconstitute(7L, 1L, StrategyState.FINALIZED, "逻辑",
                new BigDecimal("1200.0000"), new BigDecimal("1500.0000"), "仓位", "条件", "风险",
                CREATED.plusSeconds(600), 2L, CREATED, CREATED.plusSeconds(900));
        assertThat(doc.id()).isEqualTo(7L);
        assertThat(doc.projectId()).isEqualTo(1L);
        assertThat(doc.state()).isEqualTo(StrategyState.FINALIZED);
        assertThat(doc.thesis()).isEqualTo("逻辑");
        assertThat(doc.valuationLow()).isEqualByComparingTo("1200");
        assertThat(doc.valuationHigh()).isEqualByComparingTo("1500");
        assertThat(doc.positionPlan()).isEqualTo("仓位");
        assertThat(doc.buyConditions()).isEqualTo("条件");
        assertThat(doc.riskNotes()).isEqualTo("风险");
        assertThat(doc.finalizedAt()).isEqualTo(CREATED.plusSeconds(600));
        assertThat(doc.version()).isEqualTo(2L);
        assertThat(doc.createdAt()).isEqualTo(CREATED);
        assertThat(doc.updatedAt()).isEqualTo(CREATED.plusSeconds(900));
    }

    // ---- Falsifier（D10） ----

    @DisplayName("ofPredicate 构造谓词类：kind=PREDICATE、阈值非负可零、默认启用")
    @Test
    void givenPredicateInput_whenOfPredicate_thenPredicateFalsifier() {
        var f = Falsifier.ofPredicate(7L, FalsifierPredicate.PRICE_BELOW, new BigDecimal("12.34"), "跌破定稿下限");
        assertThat(f.id()).isNull();
        assertThat(f.strategyId()).isEqualTo(7L);
        assertThat(f.kind()).isEqualTo(FalsifierKind.PREDICATE);
        assertThat(f.predicate()).isEqualTo(FalsifierPredicate.PRICE_BELOW);
        assertThat(f.threshold()).isEqualByComparingTo("12.34");
        assertThat(f.note()).isEqualTo("跌破定稿下限");
        assertThat(f.eventChecked()).isFalse();
        assertThat(f.enabled()).isTrue();
        assertThat(f.createdAt()).isNotNull();
        assertThat(f.updatedAt()).isNotNull();
    }

    @DisplayName("ofPredicate 零阈值合法（非负）")
    @Test
    void givenZeroThreshold_whenOfPredicate_thenSucceed() {
        var f = Falsifier.ofPredicate(7L, FalsifierPredicate.PB_ABOVE, BigDecimal.ZERO, null);
        assertThat(f.threshold()).isEqualByComparingTo("0");
        // note 对谓词类可空
        assertThat(f.note()).isNull();
    }

    @DisplayName("ofPredicate 缺策略文档抛STRATEGY_REQUIRED")
    @Test
    void givenNullStrategyId_whenOfPredicate_thenThrowStrategyRequired() {
        assertThatThrownBy(() -> Falsifier.ofPredicate(null, FalsifierPredicate.PE_ABOVE,
                new BigDecimal("30"), "note"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STRATEGY_REQUIRED));
    }

    @DisplayName("ofPredicate 缺谓词抛PREDICATE_REQUIRED")
    @Test
    void givenNullPredicate_whenOfPredicate_thenThrowPredicateRequired() {
        assertThatThrownBy(() -> Falsifier.ofPredicate(7L, null, new BigDecimal("30"), "note"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.PREDICATE_REQUIRED));
    }

    @DisplayName("ofPredicate 阈值缺失抛THRESHOLD_INVALID")
    @Test
    void givenNullThreshold_whenOfPredicate_thenThrowThresholdInvalid() {
        assertThatThrownBy(() -> Falsifier.ofPredicate(7L, FalsifierPredicate.PRICE_BELOW, null, "note"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.THRESHOLD_INVALID));
    }

    @DisplayName("ofPredicate 负阈值抛THRESHOLD_INVALID")
    @Test
    void givenNegativeThreshold_whenOfPredicate_thenThrowThresholdInvalid() {
        assertThatThrownBy(() -> Falsifier.ofPredicate(7L, FalsifierPredicate.PRICE_BELOW,
                new BigDecimal("-0.01"), "note"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.THRESHOLD_INVALID));
    }

    @DisplayName("ofEvent 构造事件类：kind=EVENT、谓词与阈值空、靠文字说明")
    @Test
    void givenEventNote_whenOfEvent_thenEventFalsifier() {
        var f = Falsifier.ofEvent(7L, "年报审计意见非标");
        assertThat(f.kind()).isEqualTo(FalsifierKind.EVENT);
        assertThat(f.predicate()).isNull();
        assertThat(f.threshold()).isNull();
        assertThat(f.note()).isEqualTo("年报审计意见非标");
        assertThat(f.eventChecked()).isFalse();
        assertThat(f.enabled()).isTrue();
    }

    @DisplayName("ofEvent 缺策略文档抛STRATEGY_REQUIRED")
    @Test
    void givenNullStrategyId_whenOfEvent_thenThrowStrategyRequired() {
        assertThatThrownBy(() -> Falsifier.ofEvent(null, "年报审计意见非标"))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.STRATEGY_REQUIRED));
    }

    @DisplayName("ofEvent 说明缺失或空白抛NOTE_REQUIRED（事件类靠文本说明）")
    @Test
    void givenBlankNote_whenOfEvent_thenThrowNoteRequired() {
        assertThatThrownBy(() -> Falsifier.ofEvent(7L, null))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOTE_REQUIRED));
        assertThatThrownBy(() -> Falsifier.ofEvent(7L, "   "))
                .isInstanceOfSatisfying(ResearchException.class,
                        e -> assertThat(e.code()).isEqualTo(ResearchErrorCode.NOTE_REQUIRED));
    }

    @DisplayName("Falsifier reconstitute 全参还原持久化状态")
    @Test
    void givenAllFields_whenFalsifierReconstitute_thenCarriedThrough() {
        var f = Falsifier.reconstitute(11L, 7L, FalsifierKind.EVENT, null, null, true,
                "年报审计意见非标", false, 4L, CREATED, CREATED.plusSeconds(60));
        assertThat(f.id()).isEqualTo(11L);
        assertThat(f.strategyId()).isEqualTo(7L);
        assertThat(f.kind()).isEqualTo(FalsifierKind.EVENT);
        assertThat(f.predicate()).isNull();
        assertThat(f.threshold()).isNull();
        assertThat(f.eventChecked()).isTrue();
        assertThat(f.note()).isEqualTo("年报审计意见非标");
        assertThat(f.enabled()).isFalse();
        assertThat(f.version()).isEqualTo(4L);
        assertThat(f.createdAt()).isEqualTo(CREATED);
        assertThat(f.updatedAt()).isEqualTo(CREATED.plusSeconds(60));
    }

    private static StrategyDoc finalizedDoc() {
        return StrategyDoc.draftOf(1L)
                .saveDraft("旧定稿逻辑", new BigDecimal("1200"), new BigDecimal("1500"),
                        "旧仓位", "旧买入条件", "旧风险")
                .finalizeDoc();
    }
}
