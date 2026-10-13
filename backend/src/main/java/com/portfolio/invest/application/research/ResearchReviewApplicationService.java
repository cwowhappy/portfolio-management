package com.portfolio.invest.application.research;

import static com.portfolio.invest.application.research.ResearchServiceSupport.requireProject;
import static com.portfolio.invest.application.research.ResearchServiceSupport.writeEvent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.research.ResearchViews.ReviewView;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.research.EntryBatch;
import com.portfolio.invest.domain.research.EntryPlan;
import com.portfolio.invest.domain.research.RefluxState;
import com.portfolio.invest.domain.research.ResearchEntryPlanRepository;
import com.portfolio.invest.domain.research.ResearchErrorCode;
import com.portfolio.invest.domain.research.ResearchException;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.Review;
import com.portfolio.invest.domain.research.ReviewRepository;
import com.portfolio.invest.domain.wiki.WikiEntry;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 复盘用例族（F13·F14 复盘 CRUD·定格快照 / F16 wiki 回流）。2026-10-12 按用例族拆分自原
 * {@link ResearchApplicationService}（纯搬移，行为不变）；事务边界在门面方法上，
 * 本类为门面手工装配、非 Spring bean。
 *
 * <p>wiki 回流直建照 journal writeEvent 计划裁定：不经 WikiApplicationService，
 * 避免 command 面拉宽；多写操作（项目落库 + 事件）同事务（门面 @Transactional）。
 */
public class ResearchReviewApplicationService {

    private static final Logger log = LoggerFactory.getLogger(ResearchReviewApplicationService.class);

    /** 回流 wiki 条目 category 标记（F16：复用 RESEARCH_NOTE 三类型不扩枚举，S2 与 SOP_TEMPLATE 同法）。 */
    static final String SOP_REVIEW_CATEGORY = "SOP_REVIEW";

    private final ResearchProjectRepository repository;
    private final JournalEntryRepository journalRepository;
    private final ResearchEntryPlanRepository entryPlanRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewSnapshotComposer reviewComposer;
    private final WikiEntryRepository wikiEntryRepository;
    private final ObjectMapper mapper;
    private final Clock clock;

    public ResearchReviewApplicationService(ResearchProjectRepository repository,
                                            JournalEntryRepository journalRepository,
                                            ResearchEntryPlanRepository entryPlanRepository,
                                            ReviewRepository reviewRepository,
                                            ReviewSnapshotComposer reviewComposer,
                                            WikiEntryRepository wikiEntryRepository,
                                            ObjectMapper mapper,
                                            Clock clock) {
        this.repository = repository;
        this.journalRepository = journalRepository;
        this.entryPlanRepository = entryPlanRepository;
        this.reviewRepository = reviewRepository;
        this.reviewComposer = reviewComposer;
        this.wikiEntryRepository = wikiEntryRepository;
        this.mapper = mapper;
        this.clock = clock;
    }

    /** 复盘列表：仓库 periodStart 倒序透传；快照原样回显（读路径不复算，F14 定格）。 */
    public List<ReviewView> getReviews(Long userId, Long projectId) {
        requireProject(repository, userId, projectId);
        return reviewRepository.findByProjectId(projectId).stream().map(ReviewView::from).toList();
    }

    /**
     * 创建复盘（创建即定格）：{@link ReviewSnapshotComposer} 组装带口径标注的快照 JSONB
     * （区间收益/后续走势/归因窗口，写入后无任何重算入口）+ 从快照解析自动圈选 trade_ids
     * （D11 时间窗口径留痕于快照，软引用列此后可经 PUT 手动修正）。
     * F08 时间线：创建即写「复盘创建：&lt;档位&gt;」事件（与回流事件同入项目时间线）。
     */
    public ReviewView createReview(Long userId, Long projectId,
                                   ResearchApplicationService.CreateReviewCommand cmd) {
        ResearchProject project = requireProject(repository, userId, projectId);
        List<EntryBatch> batches = entryPlanRepository.findByProjectId(projectId)
                .map(EntryPlan::batches)
                .orElse(List.of());
        String snapshot = reviewComposer.compose(userId, project, cmd.periodStart(), cmd.periodEnd(), batches);
        Review saved = reviewRepository.save(Review.create(projectId, cmd.tier(),
                cmd.periodStart(), cmd.periodEnd(), snapshot, autoCircle(snapshot), clock.instant()));
        writeEvent(journalRepository, clock, project, "复盘创建：" + cmd.tier().label(),
                cmd.tier().label() + " " + cmd.periodStart() + "~" + cmd.periodEnd() + " 创建，快照已定格");
        return ReviewView.from(saved);
    }

    /**
     * 修正复盘（PUT 整替）：answers/overrides/narrative/trade_ids 整组替换（域内去重排序收敛，
     * Review Focus 5）；快照与回流状态无变更入口（F14「不复算历史」）。REFLOWN 后仍可修正
     * 叙述，已建 wiki 条目不回写（宽容口径）。
     */
    public ReviewView updateReview(Long userId, Long projectId, Long reviewId,
                                   ResearchApplicationService.UpdateReviewCommand cmd) {
        requireProject(repository, userId, projectId);
        Review existing = requireReview(projectId, reviewId);
        Review saved = reviewRepository.save(existing.correct(toJson(cmd.answers()), toJson(cmd.overrides()),
                cmd.narrative(), cmd.tradeIds(), clock.instant()));
        return ReviewView.from(saved);
    }

    /**
     * 确认回流（F16 用户确认后入库，不自动）：建 wiki RESEARCH_NOTE（title=复盘·项目·期间、
     * category=SOP_REVIEW、projectId 软引用、内容=复盘叙述——经 {@link WikiEntryRepository}
     * 直建照 journal writeEvent 计划裁定：不经 WikiApplicationService，避免 command 面拉宽）
     * → {@code refluxConfirm}。REFLOWN 态幂等直接返回既有条目（Review Focus 4）；
     * wiki 写异常降级抛 {@code REFLUX_WIKI_UNAVAILABLE}（502）——本事务尚未写复盘行，
     * 既有行保持 PENDING、不阻断复盘保存，可重试。
     */
    public ReviewView refluxReview(Long userId, Long projectId, Long reviewId) {
        ResearchProject project = requireProject(repository, userId, projectId);
        Review review = requireReview(projectId, reviewId);
        if (review.refluxState() == RefluxState.REFLOWN) {
            return ReviewView.from(review);
        }
        if (review.narrative() == null || review.narrative().isBlank()) {
            throw new ResearchException(ResearchErrorCode.REFLUX_NARRATIVE_REQUIRED,
                    "回流前请先填写复盘叙述（将写入知识库条目内容）");
        }
        WikiEntry entry;
        try {
            entry = wikiEntryRepository.save(WikiEntry.create(userId, WikiEntryType.RESEARCH_NOTE,
                    "复盘·" + project.title() + "·" + review.periodStart() + "~" + review.periodEnd(),
                    review.narrative(), SOP_REVIEW_CATEGORY, null, clock.instant(), projectId));
        } catch (RuntimeException e) {
            // 降级根因留痕（ResearchException 无 cause 构造器，异常经日志链接——照 UserAdminApplicationService 先例）
            log.warn("回流 wiki 写入失败（projectId={}，reviewId={}），降级保持 PENDING", projectId, reviewId, e);
            throw new ResearchException(ResearchErrorCode.REFLUX_WIKI_UNAVAILABLE,
                    "知识库写入失败，复盘已保留，请稍后重试回流");
        }
        Review confirmed = reviewRepository.save(review.refluxConfirm(entry.id(), clock.instant()));
        // F08 时间线：回流成功才写事件（幂等早退/降级路径不写）
        writeEvent(journalRepository, clock, project, "复盘回流知识库", "复盘叙述已写入知识库条目「" + entry.title() + "」");
        return ReviewView.from(confirmed);
    }

    /** 自动圈选（D11）：从 Composer 定格快照解析 tradeIds（缺键/非数组容忍为空；去重排序收敛由域承担）。 */
    private List<Long> autoCircle(String snapshotJson) {
        JsonNode ids;
        try {
            ids = mapper.readTree(snapshotJson).path("tradeIds");
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("复盘快照解析失败", e); // Composer 产物为合法 JSON，防御性兜底
        }
        if (!ids.isArray()) {
            return List.of();
        }
        List<Long> out = new ArrayList<>(ids.size());
        for (JsonNode id : ids) {
            out.add(id.asLong());
        }
        return out;
    }

    /** wire JsonNode → 域 JSON 字符串（null/JSON null 归一为 null，未作答语义）。 */
    private String toJson(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("复盘 JSON 字段序列化失败", e);
        }
    }

    /** 项目域内复盘行读取（照 findHit 口径：越项目/不存在一律 NOT_FOUND，不泄漏存在性）。 */
    private Review requireReview(Long projectId, Long reviewId) {
        return reviewRepository.findByIdAndProjectId(reviewId, projectId)
                .orElseThrow(() -> new ResearchException(ResearchErrorCode.NOT_FOUND, "复盘不存在"));
    }
}
