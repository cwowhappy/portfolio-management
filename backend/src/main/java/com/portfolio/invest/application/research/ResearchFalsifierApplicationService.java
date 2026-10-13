package com.portfolio.invest.application.research;

import static com.portfolio.invest.application.research.ResearchServiceSupport.requireProject;
import static com.portfolio.invest.application.research.ResearchServiceSupport.writeEvent;

import com.portfolio.invest.application.research.ResearchViews.FalsifierHitView;
import com.portfolio.invest.application.research.ResearchViews.FalsifierReviewView;
import com.portfolio.invest.application.research.ResearchViews.FalsifierView;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierEvaluator;
import com.portfolio.invest.domain.research.FalsifierHit;
import com.portfolio.invest.domain.research.FalsifierHitResult;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierReview;
import com.portfolio.invest.domain.research.FalsifierReviewRepository;
import com.portfolio.invest.domain.research.MarketSnapshot;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.StrategyDoc;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 证伪用例族（D10 证伪条件集 / D21 证伪命中合并视图 / F15 证伪评审）。2026-10-12 按用例族
 * 拆分自原 {@link ResearchApplicationService}（纯搬移，行为不变）；事务边界在门面方法上，
 * 本类为门面手工装配、非 Spring bean。检查上下文组装委托 {@link CheckOrchestration}。
 */
public class ResearchFalsifierApplicationService {

    private final ResearchProjectRepository repository;
    private final JournalEntryRepository journalRepository;
    private final ResearchCheckRepository checkRepository;
    private final FalsifierReviewRepository falsifierReviewRepository;
    private final MarketSnapshotAssembler snapshotAssembler;
    private final Clock clock;

    public ResearchFalsifierApplicationService(ResearchProjectRepository repository,
                                               JournalEntryRepository journalRepository,
                                               ResearchCheckRepository checkRepository,
                                               FalsifierReviewRepository falsifierReviewRepository,
                                               MarketSnapshotAssembler snapshotAssembler,
                                               Clock clock) {
        this.repository = repository;
        this.journalRepository = journalRepository;
        this.checkRepository = checkRepository;
        this.falsifierReviewRepository = falsifierReviewRepository;
        this.snapshotAssembler = snapshotAssembler;
        this.clock = clock;
    }

    /** 证伪条件集查询：未建策略返回空列表（与详情视图 falsifiers=[] 一致）。 */
    public List<FalsifierView> getFalsifiers(Long userId, Long projectId) {
        requireProject(repository, userId, projectId);
        return repository.findStrategy(projectId)
                .map(doc -> repository.findFalsifiers(doc.id()).stream().map(FalsifierView::from).toList())
                .orElse(List.of());
    }

    /** 证伪条件整替保存（D10）：须先有策略文档（STRATEGY_REQUIRED）；字段校验由 Falsifier 工厂承担。 */
    public List<FalsifierView> saveFalsifiers(Long userId, Long projectId,
                                              List<ResearchApplicationService.SaveFalsifierItem> items) {
        requireProject(repository, userId, projectId);
        StrategyDoc strategy = repository.findStrategy(projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.STRATEGY_REQUIRED, "请先保存策略草稿"));
        List<Falsifier> falsifiers = items.stream()
                .map(item -> switch (item.kind()) {
                    case PREDICATE -> Falsifier.ofPredicate(strategy.id(), item.predicate(),
                            item.threshold(), item.note(), clock.instant());
                    case EVENT -> Falsifier.ofEvent(strategy.id(), item.note(),
                            Boolean.TRUE.equals(item.eventChecked()), clock.instant());
                })
                .toList();
        repository.saveFalsifiers(strategy.id(), falsifiers);
        return repository.findFalsifiers(strategy.id()).stream().map(FalsifierView::from).toList();
    }

    /**
     * 证伪命中合并视图：实时求值（FalsifierEvaluator 纯函数，不落库）+ 历史 hit 留痕行。
     * EVENT 条目按勾选状态呈现（Ruling-18：已确认事件/待人工勾选）；历史行仅 PREDICATE 命中
     * （Ruling-18 落表口径，T5 日终扫描同口径）。
     */
    public List<FalsifierHitView> getHits(Long userId, Long projectId) {
        ResearchProject project = requireProject(repository, userId, projectId);
        List<Falsifier> falsifiers = falsifiersOf(projectId);
        List<FalsifierHitView> views = new ArrayList<>();
        if (!falsifiers.isEmpty()) {
            MarketSnapshot snapshot = snapshotAssembler.assemble(project.stockCode());
            for (FalsifierHitResult result : FalsifierEvaluator.evaluate(falsifiers, snapshot)) {
                views.add(realtimeView(result));
            }
        }
        Map<Long, Falsifier> byId = falsifiers.stream()
                .collect(Collectors.toMap(Falsifier::id, Function.identity(), (a, b) -> a));
        for (FalsifierHit hit : checkRepository.findHits(projectId)) {
            views.add(historyView(hit, byId));
        }
        return views;
    }

    /** 评审留痕列表（前端列表用）：createdAt 倒序；suggestStrategyRevise 由结论推导。 */
    public List<FalsifierReviewView> getFalsifierReviews(Long userId, Long projectId) {
        requireProject(repository, userId, projectId);
        return falsifierReviewRepository.findReviews(projectId).stream()
                .map(review -> FalsifierReviewView.of(review, null))
                .toList();
    }

    /**
     * 提交证伪评审（F15）：append-only 落库 + hit 回填 reviewId + journal 事件
     * 「证伪评审：&lt;结论&gt;」同事务；hitId 须为本项目命中行（否则 NOT_FOUND，不落库）。
     * REVISE 只置 suggestStrategyRevise 提示位、<b>不自动改策略状态</b>（Review Focus 3：
     * 落库与策略修订分离，避免隐式派生——由前端引导用户显式 revise）。
     */
    public FalsifierReviewView submitFalsifierReview(Long userId, Long projectId,
                                                     ResearchApplicationService.SubmitFalsifierReviewCommand cmd) {
        ResearchProject project = requireProject(repository, userId, projectId);
        // 域校验（结论/理由）先于 hit 资源解析：请求体非法 → 422，与 hitId 无关且零写库
        FalsifierReview review = FalsifierReview.create(projectId, cmd.conclusion(), cmd.reason(),
                clock.instant());
        if (cmd.hitId() != null) {
            checkRepository.findHit(projectId, cmd.hitId())
                    .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "证伪命中留痕不存在"));
        }
        FalsifierReview inserted = falsifierReviewRepository.insert(review);
        if (cmd.hitId() != null) {
            falsifierReviewRepository.attachReview(cmd.hitId(), inserted.id());
        }
        writeEvent(journalRepository, clock, project, "证伪评审：" + cmd.conclusion().label(),
                "证伪命中评审结论「" + cmd.conclusion().label() + "」，理由：" + cmd.reason());
        return FalsifierReviewView.of(inserted, cmd.hitId());
    }

    /** 项目当前证伪条件集：无策略文档 → 空列表（与详情视图口径一致）。 */
    private List<Falsifier> falsifiersOf(Long projectId) {
        return repository.findStrategy(projectId)
                .map(doc -> repository.findFalsifiers(doc.id()))
                .orElse(List.of());
    }

    /** 实时求值行：EVENT 条目按勾选状态改写 basis/pending（Ruling-18，evaluator 不消费 eventChecked）。 */
    private static FalsifierHitView realtimeView(FalsifierHitResult result) {
        Falsifier falsifier = result.falsifier();
        if (falsifier.kind() == FalsifierKind.EVENT) {
            boolean checked = falsifier.eventChecked();
            return new FalsifierHitView(null, falsifier.id(), falsifier.kind(), null, null,
                    falsifier.note(), checked, false, !checked, false,
                    checked ? "已确认事件" : "待人工勾选", true, null);
        }
        return new FalsifierHitView(null, falsifier.id(), falsifier.kind(), falsifier.predicate(),
                falsifier.threshold(), falsifier.note(), falsifier.eventChecked(),
                result.hit(), result.pending(), result.skipped(), result.basis(), true, null);
    }

    /** 历史留痕行：falsifier 现态回连（条件已删则现态字段 null，仅保 basis/时间）。 */
    private static FalsifierHitView historyView(FalsifierHit hit, Map<Long, Falsifier> byId) {
        Falsifier falsifier = byId.get(hit.falsifierId());
        return new FalsifierHitView(hit.id(), hit.falsifierId(),
                falsifier == null ? null : falsifier.kind(),
                falsifier == null ? null : falsifier.predicate(),
                falsifier == null ? null : falsifier.threshold(),
                falsifier == null ? null : falsifier.note(),
                falsifier != null && falsifier.eventChecked(),
                false, false, false, hit.basis(), false, hit.createdAt());
    }
}
