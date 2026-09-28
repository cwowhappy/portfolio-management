package com.portfolio.invest.bdd.steps;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.application.research.CreateProjectCommand;
import com.portfolio.invest.application.research.ResearchApplicationService;
import com.portfolio.invest.application.research.ResearchApplicationService.CreateReviewCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.SaveFalsifierItem;
import com.portfolio.invest.application.research.ResearchApplicationService.SubmitFalsifierReviewCommand;
import com.portfolio.invest.application.research.ResearchApplicationService.UpdateReviewCommand;
import com.portfolio.invest.application.research.SaveStrategyCommand;
import com.portfolio.invest.application.research.ResearchViews.ReviewView;
import com.portfolio.invest.domain.research.FalsifierHit;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierPredicate;
import com.portfolio.invest.domain.research.RefluxState;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ReviewConclusion;
import com.portfolio.invest.domain.research.ReviewTier;
import com.portfolio.invest.domain.research.StrategyState;
import com.portfolio.invest.domain.wiki.WikiEntryRepository;
import com.portfolio.invest.domain.wiki.WikiEntryType;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 复盘闭环步骤（MS-27 验收链路）：照 {@link JournalSteps} 先例直调 ApplicationService +
 * 真实 PG（Testcontainers），不依赖行情（无持仓复盘的快照字段应为「无数据」标注）。
 * 命中行 review_id 回填断言经 JdbcTemplate 直查列（域记录不含回填位，照
 * ResearchEntryPlanCheckRepositoryImplTest 的集成断言口径）。
 */
public class ResearchFlowSteps {

    @Autowired
    ResearchApplicationService researchService;

    @Autowired
    ResearchCheckRepository checkRepository;

    @Autowired
    WikiEntryRepository wikiEntryRepository;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ScenarioContext ctx;

    @假如("立项研究项目 {string}（标的 {string} {string}）")
    public void 立项研究项目(String title, String stockCode, String stockName) {
        var view = researchService.createProject(ctx.getUserId(),
                new CreateProjectCommand(stockCode, stockName, null, title, false));
        ctx.setResearchProjectId(view.id());
    }

    @假如("暂存策略草稿并定稿，估值区间 {bigdecimal} 至 {bigdecimal}")
    public void 暂存并定稿策略(BigDecimal valuationLow, BigDecimal valuationHigh) {
        researchService.saveStrategyDraft(ctx.getUserId(), ctx.getResearchProjectId(),
                new SaveStrategyCommand("品牌护城河与估值修复（BDD）", valuationLow, valuationHigh,
                        null, null, null));
        researchService.finalizeStrategy(ctx.getUserId(), ctx.getResearchProjectId());
    }

    // 注册一次即可匹配任意关键字位置（cucumber 按表达式文本匹配，与 gherkin 关键字无关）
    @当("创建档位为 {string} 的复盘，区间 {string} 至 {string}")
    public void 创建复盘(String tierLabel, String from, String to) {
        ReviewView view = researchService.createReview(ctx.getUserId(), ctx.getResearchProjectId(),
                new CreateReviewCommand(tierOf(tierLabel), LocalDate.parse(from), LocalDate.parse(to)));
        ctx.setResearchReviewId(view.id());
        ctx.setResearchSnapshotJson(view.snapshot());
    }

    @那么("快照价格口径为 {string}，净值口径含 {string}")
    public void 快照含口径(String priceBasis, String navFragment) throws Exception {
        JsonNode snapshot = mapper.readTree(ctx.getResearchSnapshotJson());
        assertThat(snapshot.path("priceBasis").asText()).isEqualTo(priceBasis);
        assertThat(snapshot.path("navBasis").asText()).contains(navFragment);
    }

    @那么("无持仓数据的快照字段标注 {string}")
    public void 无数据标注(String marker) throws Exception {
        JsonNode snapshot = mapper.readTree(ctx.getResearchSnapshotJson());
        assertThat(snapshot.path("navSeries").asText()).isEqualTo(marker);
        assertThat(snapshot.path("periodReturn").asText()).isEqualTo(marker);
        assertThat(snapshot.path("trades").asText()).isEqualTo(marker);
        assertThat(snapshot.path("tradeIds").isArray()).isTrue();
        assertThat(snapshot.path("tradeIds")).isEmpty();
    }

    @当("修正复盘作答（叙述 {string}）")
    public void 修正复盘(String narrative) throws Exception {
        researchService.updateReview(ctx.getUserId(), ctx.getResearchProjectId(), ctx.getResearchReviewId(),
                new UpdateReviewCommand(mapper.readTree("""
                        {"4.1":"决策质量：检查单全项执行","4.4":"决策对/结果错（运气坏）"}"""),
                        null, narrative, null));
    }

    @那么("该复盘快照与创建时定格一致")
    public void 快照定格一致() throws Exception {
        var view = currentReview();
        // jsonb 键序不保证：按语义（JsonNode 等值）比较，而非逐字节
        assertThat(mapper.readTree(view.snapshot())).isEqualTo(mapper.readTree(ctx.getResearchSnapshotJson()));
    }

    @当("确认回流知识库")
    @当("再次确认回流知识库")
    public void 确认回流() {
        ReviewView view = researchService.refluxReview(ctx.getUserId(), ctx.getResearchProjectId(),
                ctx.getResearchReviewId());
        ctx.setResearchWikiEntryId(view.wikiEntryId());
    }

    @那么("回流状态为 {string} 且记录知识库条目号")
    public void 回流状态(String stateLabel) {
        var view = currentReview();
        assertThat(view.refluxState()).isEqualTo(refluxStateOf(stateLabel));
        assertThat(view.wikiEntryId()).isNotNull().isEqualTo(ctx.getResearchWikiEntryId());
    }

    @那么("知识库研究笔记含 {string} 条目，标题以 {string} 开头")
    public void 知识库含回流条目(String category, String titlePrefix) {
        var entries = wikiEntryRepository.findByUserId(ctx.getUserId(), WikiEntryType.RESEARCH_NOTE);
        assertThat(entries).anySatisfy(entry -> {
            assertThat(entry.category()).isEqualTo(category);
            assertThat(entry.title()).startsWith(titlePrefix);
            assertThat(entry.projectId()).isEqualTo(ctx.getResearchProjectId());
            assertThat(entry.content()).isEqualTo("本期区间收益为正，加仓节奏符合计划");
        });
    }

    @那么("返回同一知识库条目号，且该复盘仅一条知识库条目")
    public void 回流幂等() {
        assertThat(ctx.getResearchWikiEntryId()).isEqualTo(currentReview().wikiEntryId());
        var entries = wikiEntryRepository.findByUserIdAndProjectId(ctx.getUserId(), ctx.getResearchProjectId());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().id()).isEqualTo(ctx.getResearchWikiEntryId());
    }

    @假如("配置谓词证伪条件 {string} 阈值 {bigdecimal}")
    public void 配置证伪条件(String predicate, BigDecimal threshold) {
        var views = researchService.saveFalsifiers(ctx.getUserId(), ctx.getResearchProjectId(),
                List.of(new SaveFalsifierItem(FalsifierKind.PREDICATE,
                        FalsifierPredicate.valueOf(predicate), threshold, "收盘跌破估值区间下沿（BDD）")));
        ctx.setFalsifierId(views.get(0).id());
    }

    @假如("落库一条证伪命中留痕")
    public void 落库命中留痕() {
        // createdAt 由调用方赋值（照 ResearchFalsifierScanService 日终扫描同法，列 NOT NULL）
        FalsifierHit hit = checkRepository.insertHit(new FalsifierHit(null, ctx.getResearchProjectId(),
                ctx.getFalsifierId(), "收盘价跌破阈值（BDD 命中）", Instant.now()));
        ctx.setFalsifierHitId(hit.id());
    }

    @当("提交证伪评审，结论 {string}，理由 {string}")
    @当("再次提交证伪评审，结论 {string}，理由 {string}")
    public void 提交证伪评审(String conclusionLabel, String reason) {
        var view = researchService.submitFalsifierReview(ctx.getUserId(), ctx.getResearchProjectId(),
                new SubmitFalsifierReviewCommand(ctx.getFalsifierHitId(),
                        conclusionOf(conclusionLabel), reason));
        ctx.setLastFalsifierReviewId(view.id());
    }

    @那么("评审留痕可见该结论且建议修订策略")
    public void 评审留痕可见() {
        var reviews = researchService.getFalsifierReviews(ctx.getUserId(), ctx.getResearchProjectId());
        var last = reviews.stream()
                .filter(r -> r.id().equals(ctx.getLastFalsifierReviewId()))
                .findFirst().orElseThrow();
        assertThat(last.conclusion()).isEqualTo(ReviewConclusion.REVISE);
        assertThat(last.suggestStrategyRevise()).isTrue(); // 提示位：不自动改策略状态
    }

    @那么("策略状态仍为 {string}")
    public void 策略状态(String state) {
        assertThat(researchService.getStrategy(ctx.getUserId(), ctx.getResearchProjectId()).state())
                .isEqualTo(StrategyState.valueOf(state));
    }

    @那么("命中行已关联首评")
    public void 命中关联首评() {
        assertThat(hitReviewId()).isEqualTo(ctx.getLastFalsifierReviewId());
        ctx.setFirstFalsifierReviewId(ctx.getLastFalsifierReviewId());
    }

    @那么("评审留痕共 {int} 条，命中行仍关联首评")
    public void 评审留痕共(int count) {
        assertThat(researchService.getFalsifierReviews(ctx.getUserId(), ctx.getResearchProjectId()))
                .hasSize(count);
        Long attached = hitReviewId();
        assertThat(attached).isEqualTo(ctx.getFirstFalsifierReviewId())
                .isNotEqualTo(ctx.getLastFalsifierReviewId()); // 首评占据软引用，二次评审不覆盖
    }

    private ReviewView currentReview() {
        return researchService.getReviews(ctx.getUserId(), ctx.getResearchProjectId()).stream()
                .filter(r -> r.id().equals(ctx.getResearchReviewId()))
                .findFirst().orElseThrow();
    }

    /** hit.review_id 直查（域记录不含回填位；照 ResearchEntryPlanCheckRepositoryImplTest 口径）。 */
    private Long hitReviewId() {
        return jdbcTemplate.queryForObject(
                "SELECT review_id FROM research_falsifier_hit WHERE id = ?",
                Long.class, ctx.getFalsifierHitId());
    }

    private static ReviewTier tierOf(String label) {
        return Arrays.stream(ReviewTier.values())
                .filter(t -> t.label().equals(label + "复盘"))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("未知复盘档位：" + label));
    }

    private static ReviewConclusion conclusionOf(String label) {
        return Arrays.stream(ReviewConclusion.values())
                .filter(c -> c.label().equals(label))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("未知评审结论：" + label));
    }

    private static RefluxState refluxStateOf(String label) {
        return switch (label) {
            case "待回流" -> RefluxState.PENDING;
            case "已确认" -> RefluxState.CONFIRMED;
            case "已回流" -> RefluxState.REFLOWN;
            default -> throw new IllegalArgumentException("未知回流状态：" + label);
        };
    }
}
