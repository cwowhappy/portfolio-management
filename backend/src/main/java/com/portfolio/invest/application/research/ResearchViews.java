package com.portfolio.invest.application.research;

import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierPredicate;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.StageCompletion;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** research 域读视图集合（照 wiki/journal application 视图先例，record 内聚）。 */
public final class ResearchViews {

    private ResearchViews() {}

    /** 项目行视图（列表与详情共用）。 */
    public record ProjectView(Long id, String stockCode, String stockName, String industryCode,
                              String title, ResearchStage currentStage, ProjectStatus status,
                              Instant createdAt, Instant updatedAt) {

        public static ProjectView from(ResearchProject p) {
            return new ProjectView(p.id(), p.stockCode(), p.stockName(), p.industryCode(), p.title(),
                    p.currentStage(), p.status(), p.createdAt(), p.updatedAt());
        }
    }

    /**
     * 项目详情读模型：完成度为 {@code StageCompletionService} 唯一计算点的产出（S6/NFR-1，
     * 前端不重复实现）；strategy 为 null 表示尚未创建策略文档（falsifiers 同步为空）。
     */
    public record ProjectDetailView(ProjectView project,
                                    Map<ResearchStage, StageCompletion> completions,
                                    StrategyView strategy,
                                    List<FalsifierView> falsifiers) {

        public static ProjectDetailView of(ResearchProject project,
                                           Map<ResearchStage, StageCompletion> completions,
                                           StrategyDoc strategy,
                                           List<Falsifier> falsifiers) {
            return new ProjectDetailView(ProjectView.from(project), completions,
                    strategy == null ? null : StrategyView.from(strategy),
                    falsifiers.stream().map(FalsifierView::from).toList());
        }
    }

    /** 策略文档视图。 */
    public record StrategyView(Long id, StrategyState state, String thesis,
                               BigDecimal valuationLow, BigDecimal valuationHigh,
                               String positionPlan, String buyConditions, String riskNotes,
                               Instant finalizedAt, Instant updatedAt) {

        public static StrategyView from(StrategyDoc d) {
            return new StrategyView(d.id(), d.state(), d.thesis(), d.valuationLow(), d.valuationHigh(),
                    d.positionPlan(), d.buyConditions(), d.riskNotes(), d.finalizedAt(), d.updatedAt());
        }
    }

    /** 证伪条件视图。 */
    public record FalsifierView(Long id, FalsifierKind kind, FalsifierPredicate predicate,
                                BigDecimal threshold, boolean eventChecked, String note, boolean enabled) {

        public static FalsifierView from(Falsifier f) {
            return new FalsifierView(f.id(), f.kind(), f.predicate(), f.threshold(),
                    f.eventChecked(), f.note(), f.enabled());
        }
    }
}
