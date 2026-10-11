package com.portfolio.invest.application.research;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.portfolio.invest.domain.research.CheckItemResult;
import com.portfolio.invest.domain.research.CheckRecord;
import com.portfolio.invest.domain.research.CheckResult;
import com.portfolio.invest.domain.research.CheckType;
import com.portfolio.invest.domain.research.EntryBatch;
import com.portfolio.invest.domain.research.EntryPlan;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierPredicate;
import com.portfolio.invest.domain.research.FalsifierReview;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.RefluxState;
import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ResearchFeedback;
import com.portfolio.invest.domain.research.ResearchReport;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.Review;
import com.portfolio.invest.domain.research.ReviewConclusion;
import com.portfolio.invest.domain.research.ReviewTier;
import com.portfolio.invest.domain.research.StageCompletion;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** research 域读视图集合（照 wiki/journal application 视图先例，record 内聚）。 */
public final class ResearchViews {

    private ResearchViews() {}

    /** 项目行视图（列表与详情共用）；intelligenceAlertEnabled 为项目级情报提醒开关（M16-F11）。 */
    public record ProjectView(Long id, String stockCode, String stockName, String industryCode,
                              String title, ResearchStage currentStage, ProjectStatus status,
                              boolean intelligenceAlertEnabled,
                              Instant createdAt, Instant updatedAt) {

        public static ProjectView from(ResearchProject p) {
            return new ProjectView(p.id(), p.stockCode(), p.stockName(), p.industryCode(), p.title(),
                    p.currentStage(), p.status(), p.intelligenceAlertEnabled(), p.createdAt(), p.updatedAt());
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

    /** 建仓批次视图（research_entry_batch 行）。 */
    public record EntryBatchView(int seq, BigDecimal priceLow, BigDecimal priceHigh, long quantity,
                                 BigDecimal amount, BigDecimal ratio) {

        public static EntryBatchView from(EntryBatch b) {
            return new EntryBatchView(b.seq(), b.priceLow(), b.priceHigh(), b.quantity(),
                    b.amount(), b.ratio());
        }
    }

    /** 建仓计划视图：kellyRatio 为系统算术结果（D23 只做算术），读时计算不落库冗余。 */
    public record EntryPlanView(Long id, BigDecimal winRate, BigDecimal payoffRatio, BigDecimal kellyRatio,
                                List<EntryBatchView> batches, Instant createdAt, Instant updatedAt) {

        public static EntryPlanView from(EntryPlan p) {
            return new EntryPlanView(p.id(), p.winRate(), p.payoffRatio(), p.kellyRatio(),
                    p.batches().stream().map(EntryBatchView::from).toList(), p.createdAt(), p.updatedAt());
        }
    }

    /** 纪律检查留痕视图（append-only，items 为提交时快照）。 */
    public record CheckRecordView(Long id, CheckType checkType, List<CheckItemResult> items,
                                  CheckResult result, String overrideReason, Instant createdAt) {

        public static CheckRecordView from(CheckRecord r) {
            return new CheckRecordView(r.id(), r.checkType(), r.items(), r.result(),
                    r.overrideReason(), r.createdAt());
        }
    }

    /**
     * 证伪命中合并视图行（D21）：realtime=true 实时求值条目（每次求值、不落库，hitAt=null）；
     * realtime=false 历史 hit 留痕行（id/hitAt 为落库标识）。EVENT 条目按勾选状态呈现
     * （Ruling-18：eventChecked=true → basis「已确认事件」pending=false；false →「待人工勾选」）。
     * 历史行的 falsifier 现态字段（kind/predicate/threshold/note/eventChecked）在条件已被
     * 整替删除时为 null，仅保留 basis 与 falsifierId。
     */
    public record FalsifierHitView(Long id, Long falsifierId, FalsifierKind kind, FalsifierPredicate predicate,
                                   BigDecimal threshold, String note, boolean eventChecked,
                                   boolean hit, boolean pending, boolean skipped, String basis,
                                   boolean realtime, Instant hitAt) {
    }

    /**
     * 证伪评审留痕视图（append-only）：suggestStrategyRevise 提示位仅 REVISE=true——
     * 提示前端引导用户显式 revise，<b>不自动改策略状态</b>（Review Focus 3：落库与策略修订分离）。
     * hitId 为提交时回连的命中行（hit→review 单向软引用，列表行不反连、为 null）。
     */
    public record FalsifierReviewView(Long id, Long projectId, Long hitId, ReviewConclusion conclusion,
                                      String reason, boolean suggestStrategyRevise, Instant createdAt) {

        public static FalsifierReviewView of(FalsifierReview review, Long hitId) {
            return new FalsifierReviewView(review.id(), review.projectId(), hitId, review.conclusion(),
                    review.reason(), review.conclusion() == ReviewConclusion.REVISE, review.createdAt());
        }
    }

    /**
     * 复盘视图（F13/F14/F16）：snapshot/answers/overrides 为域内 JSON 字符串，经
     * {@link JsonRawValue} 原样内联到 wire（对象而非转义字符串；DB jsonb 保证其有效性，
     * 键序可能与创建时 Composer 输出不同——定格按语义比较）。answers/overrides 为 null
     * 表示尚未作答；tradeIds 为归因圈选（自动圈选后可手动修正）。
     */
    public record ReviewView(Long id, Long projectId, ReviewTier tier,
                             LocalDate periodStart, LocalDate periodEnd,
                             @JsonRawValue String snapshot, @JsonRawValue String answers,
                             String narrative, @JsonRawValue String overrides,
                             List<Long> tradeIds, RefluxState refluxState, Long wikiEntryId,
                             Instant createdAt, Instant updatedAt) {

        public static ReviewView from(Review review) {
            return new ReviewView(review.id(), review.projectId(), review.tier(),
                    review.periodStart(), review.periodEnd(), review.snapshotJson(),
                    review.answersJson(), review.narrative(), review.overridesJson(),
                    review.tradeIds(), review.refluxState(), review.wikiEntryId(),
                    review.createdAt(), review.updatedAt());
        }
    }

    /** 模板改进建议视图（F16 只收集不生效；reviewId 为来源复盘可空软引用）。 */
    public record FeedbackView(Long id, Long projectId, Long reviewId, ResearchStage stage,
                               String content, Instant createdAt) {

        public static FeedbackView from(ResearchFeedback feedback) {
            return new FeedbackView(feedback.id(), feedback.projectId(), feedback.reviewId(),
                    feedback.stage(), feedback.content(), feedback.createdAt());
        }
    }

    /**
     * 研报任务轮询视图（MS-32 §3.12 GET report-tasks/latest）：终态 FAILED 透出
     * errorCode/errorMessage 诊断；SUCCEEDED 透出 reportId 供前端跳转详情，其余态 null。
     */
    public record TaskStatusView(String status, String stage, long tokenUsage, String errorCode,
                                 String errorMessage, Long reportId) {

        public static TaskStatusView from(ReportTask task, Long reportId) {
            return new TaskStatusView(task.status().name(), task.stage().name(), task.tokenUsage(),
                    task.errorCode(), task.errorMessage(), reportId);
        }
    }

    /** 研报摘要视图（MS-32 §3.12 项目研报列表，createdAt 倒序）。 */
    public record ReportSummaryView(Long id, String title, String status, Instant createdAt) {

        public static ReportSummaryView from(ResearchReport report) {
            return new ReportSummaryView(report.id(), report.title(), report.status().name(),
                    report.createdAt());
        }
    }

    /**
     * 研报详情视图（MS-32 F02，§3.8 staleness 注入）：假设条目携带锚点视图（tool/args/asOf
     * 为锚定真相，state/currentValue 为打开时重查的瞬态四态——不落库）；anchor 为 null 表示
     * 门未绑定假设（已出稿研报不可能，防御性兜底）。stalenessCheckedAt 为本次取数时刻
     * （TTL 缓存命中时实际重查最早可追溯至 recheck-ttl 前——缓存值即该最大陈旧窗内的重查结果）。
     */
    public record ReportDetailView(Long id, Long projectId, String title, String status,
                                   String contentMd, String limitations, Instant createdAt,
                                   Instant stalenessCheckedAt, List<AssumptionView> assumptions) {

        /** 假设条目：statement 为定稿文本，anchor 携带锚定真相与重查状态。 */
        public record AssumptionView(int seq, String statement, AnchorView anchor) {}

        /** 锚点视图：state 为 FRESH/STALE/CHANGED/UNVERIFIABLE 四态名，currentValue 不可得为 null。 */
        public record AnchorView(String tool, Map<String, Object> args, Instant asOf,
                                 String state, String currentValue) {}
    }
}
