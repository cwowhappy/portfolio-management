package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.research.CheckItemResult;
import com.portfolio.invest.domain.research.CheckOutcome;
import com.portfolio.invest.domain.research.CheckRecord;
import com.portfolio.invest.domain.research.CheckResult;
import com.portfolio.invest.domain.research.CheckType;
import com.portfolio.invest.domain.research.EntryBatch;
import com.portfolio.invest.domain.research.EntryPlan;
import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierHit;
import com.portfolio.invest.domain.research.FalsifierPredicate;
import com.portfolio.invest.domain.research.FalsifierReview;
import com.portfolio.invest.domain.research.FalsifierReviewRepository;
import com.portfolio.invest.domain.research.ResearchCheckRepository;
import com.portfolio.invest.domain.research.ResearchEntryPlanRepository;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.ReviewConclusion;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.support.PostgresTestSupport;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * P3-T4 四表 JPA 贯通：entry_plan/batch 整替往返、check_record JSONB 检查项快照往返
 * （append-only：仅 insert 路径）、falsifier_hit 读取。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({ResearchProjectRepositoryImpl.class, ResearchEntryPlanRepositoryImpl.class,
        ResearchCheckRepositoryImpl.class, FalsifierReviewRepositoryImpl.class})
class ResearchEntryPlanCheckRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private ResearchProjectRepository projectRepository;
    @Autowired
    private ResearchEntryPlanRepository entryPlanRepository;
    @Autowired
    private ResearchCheckRepository checkRepository;
    @Autowired
    private FalsifierReviewRepository falsifierReviewRepository;
    @Autowired
    private ResearchCheckRecordJpaRepository checkRecordJpa;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long seedProject() {
        Long user = jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "plan_" + System.nanoTime());
        ResearchProject project = projectRepository.save(ResearchProject.create(
                user, "600519", "贵州茅台", "801120", "茅台建仓", ResearchStage.POSITION));
        return project.id();
    }

    @DisplayName("建仓计划整替：首轮插入 plan+batches 往返保真；二轮整替旧批清空换新批")
    @Test
    @Transactional
    void givenPlan_whenSaveTwice_thenReplacedWithNewBatches() {
        Long projectId = seedProject();

        assertThat(entryPlanRepository.findByProjectId(projectId)).isEmpty();

        EntryPlan saved = entryPlanRepository.save(EntryPlan.of(projectId,
                new BigDecimal("0.6"), new BigDecimal("2.0"), List.of(
                new EntryBatch(1, new BigDecimal("12.0000"), new BigDecimal("13.0000"), 100L,
                        null, new BigDecimal("0.6000")),
                new EntryBatch(2, new BigDecimal("10.0000"), new BigDecimal("11.0000"), 100L,
                        new BigDecimal("1200.00"), new BigDecimal("0.4000")))));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.version()).isEqualTo(0L);

        EntryPlan found = entryPlanRepository.findByProjectId(projectId).orElseThrow();
        assertThat(found.winRate()).isEqualByComparingTo("0.6");
        assertThat(found.payoffRatio()).isEqualByComparingTo("2.0");
        assertThat(found.batches()).hasSize(2);
        EntryBatch first = found.batches().get(0);
        assertThat(first.seq()).isEqualTo(1);
        assertThat(first.priceLow()).isEqualByComparingTo("12");
        assertThat(first.priceHigh()).isEqualByComparingTo("13");
        assertThat(first.quantity()).isEqualTo(100L);
        assertThat(first.amount()).isNull(); // 可空金额往返保真
        assertThat(first.ratio()).isEqualByComparingTo("0.6");
        assertThat(found.batches().get(1).amount()).isEqualByComparingTo("1200");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT kelly_ratio FROM research_entry_plan WHERE project_id = ?",
                BigDecimal.class, projectId)).isEqualByComparingTo("0.4"); // 冗余算术列落档（Ruling-15）

        // 整替：两批换一批，旧 plan 行删除（每项目至多一行）
        entryPlanRepository.save(EntryPlan.of(projectId, null, null, List.of(
                new EntryBatch(1, new BigDecimal("11"), new BigDecimal("12"), 200L,
                        null, new BigDecimal("1.0")))));
        EntryPlan replaced = entryPlanRepository.findByProjectId(projectId).orElseThrow();
        assertThat(replaced.winRate()).isNull(); // 凯利参数未填（D23 手动可选）
        assertThat(replaced.batches()).hasSize(1);
        assertThat(replaced.batches().get(0).quantity()).isEqualTo(200L);
        Integer planRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM research_entry_plan WHERE project_id = ?", Integer.class, projectId);
        assertThat(planRows).isEqualTo(1);
        Integer batchRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM research_entry_batch b JOIN research_entry_plan p ON b.plan_id = p.id "
                        + "WHERE p.project_id = ?",
                Integer.class, projectId);
        assertThat(batchRows).isEqualTo(1);
    }

    @DisplayName("检查留痕 insert：items JSONB 快照经 Hibernate 往返（BigDecimal/枚举保真），无更新路径")
    @Test
    @Transactional
    void givenCheckRecord_whenInsert_thenItemsRoundTripThroughJsonb() {
        Long projectId = seedProject();
        List<CheckItemResult> items = List.of(
                new CheckItemResult("SINGLE_POSITION_RATIO", new BigDecimal("0.5"),
                        new BigDecimal("1.0"), CheckOutcome.HIT),
                new CheckItemResult("能力圈", null, null, CheckOutcome.PASS));

        CheckRecord inserted = checkRepository.insert(CheckRecord.create(projectId, CheckType.SELL,
                items, CheckResult.OVERRIDDEN, "计划外机会，仓位已复核"));

        assertThat(inserted.id()).isNotNull();
        String stored = jdbcTemplate.queryForObject(
                "SELECT items::text FROM research_check_record WHERE id = ?", String.class, inserted.id());
        assertThat(stored).contains("SINGLE_POSITION_RATIO").contains("HIT").contains("能力圈");

        // Hibernate JSONB 读回：字段级保真（读取端口到 P4 再开，此处直查 JPA 仓库验证映射）
        CheckRecord reloaded = checkRecordJpa.findById(inserted.id()).orElseThrow().toDomain();
        assertThat(reloaded.checkType()).isEqualTo(CheckType.SELL);
        assertThat(reloaded.result()).isEqualTo(CheckResult.OVERRIDDEN);
        assertThat(reloaded.overrideReason()).isEqualTo("计划外机会，仓位已复核");
        assertThat(reloaded.items()).hasSize(2);
        assertThat(reloaded.items().get(0).metric()).isEqualTo("SINGLE_POSITION_RATIO");
        assertThat(reloaded.items().get(0).threshold()).isEqualByComparingTo("0.5");
        assertThat(reloaded.items().get(0).currentValue()).isEqualByComparingTo("1.0");
        assertThat(reloaded.items().get(0).outcome()).isEqualTo(CheckOutcome.HIT);
        assertThat(reloaded.items().get(1).metric()).isEqualTo("能力圈");
        assertThat(reloaded.items().get(1).threshold()).isNull();
        assertThat(reloaded.items().get(1).outcome()).isEqualTo(CheckOutcome.PASS);
    }

    @DisplayName("证伪命中留痕：insert + findHits 倒序（append-only，仅 PREDICATE 行）")
    @Test
    @Transactional
    void givenHits_whenInsertAndFind_thenOrderedDesc() {
        Long projectId = seedProject();
        // hit.falsifier_id 为 FK：先经策略落一条真实证伪条件
        StrategyDoc strategy = projectRepository.saveStrategy(StrategyDoc.draftOf(projectId));
        projectRepository.saveFalsifiers(strategy.id(), List.of(
                Falsifier.ofPredicate(strategy.id(), FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), "跌破下限")));
        Long falsifierId = projectRepository.findFalsifiers(strategy.id()).get(0).id();

        checkRepository.insertHit(new FalsifierHit(null, projectId, falsifierId,
                "收盘价 12.10 < 下限 13.5（东财收盘 2026-09-25）",
                java.time.Instant.parse("2026-09-25T10:00:00Z")));
        checkRepository.insertHit(new FalsifierHit(null, projectId, falsifierId,
                "收盘价 12.20 < 下限 13.5（东财收盘 2026-09-26）",
                java.time.Instant.parse("2026-09-26T10:00:00Z")));

        List<FalsifierHit> hits = checkRepository.findHits(projectId);
        assertThat(hits).hasSize(2);
        assertThat(hits.get(0).createdAt()).isAfter(hits.get(1).createdAt()); // createdAt 倒序
        assertThat(hits.get(0).basis()).contains("2026-09-26");
        assertThat(hits.get(1).falsifierId()).isEqualTo(falsifierId);
        assertThat(checkRepository.findHits(projectId + 1)).isEmpty(); // 项目隔离
    }

    @DisplayName("未评审命中去重取数（T5）：仅 review_id IS NULL 行的 falsifier id 进集合")
    @Test
    @Transactional
    void givenHits_whenFindUnreviewedHitFalsifierIds_thenOnlyNullReviewRows() {
        Long projectId = seedProject();
        StrategyDoc strategy = projectRepository.saveStrategy(StrategyDoc.draftOf(projectId));
        projectRepository.saveFalsifiers(strategy.id(), List.of(
                Falsifier.ofPredicate(strategy.id(), FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), "跌破下限"),
                Falsifier.ofPredicate(strategy.id(), FalsifierPredicate.PE_ABOVE,
                        new BigDecimal("30"), "PE 过热")));
        List<Falsifier> falsifiers = projectRepository.findFalsifiers(strategy.id());
        Long f1 = falsifiers.get(0).id();
        Long f2 = falsifiers.get(1).id();

        checkRepository.insertHit(new FalsifierHit(null, projectId, f1,
                "收盘价 12.10 < 下限 13.5（东财收盘 2026-09-25）",
                java.time.Instant.parse("2026-09-25T10:00:00Z")));
        FalsifierHit reviewed = checkRepository.insertHit(new FalsifierHit(null, projectId, f2,
                "PE 31 > 上限 30（东财收盘及估值 2026-09-25）",
                java.time.Instant.parse("2026-09-25T10:00:00Z")));
        // f2 已评审回填（软引用列无 FK，模拟 P4 review 模块回写）：只剩 f1 未评审
        jdbcTemplate.update("UPDATE research_falsifier_hit SET review_id = ? WHERE id = ?",
                reviewed.id() + 1000, reviewed.id());

        assertThat(checkRepository.findUnreviewedHitFalsifierIds(projectId)).containsExactly(f1);
        assertThat(checkRepository.findUnreviewedHitFalsifierIds(projectId + 1)).isEmpty(); // 项目隔离
    }

    @DisplayName("findHit：项目域内单行读取（P4 回填前置校验）；跨项目/不存在 → empty")
    @Test
    @Transactional
    void givenHits_whenFindHit_thenScopedByProject() {
        Long projectId = seedProject();
        StrategyDoc strategy = projectRepository.saveStrategy(StrategyDoc.draftOf(projectId));
        projectRepository.saveFalsifiers(strategy.id(), List.of(
                Falsifier.ofPredicate(strategy.id(), FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), "跌破下限")));
        Long falsifierId = projectRepository.findFalsifiers(strategy.id()).get(0).id();
        FalsifierHit hit = checkRepository.insertHit(new FalsifierHit(null, projectId, falsifierId,
                "收盘价 12.10 < 下限 13.5", java.time.Instant.parse("2026-09-25T10:00:00Z")));

        assertThat(checkRepository.findHit(projectId, hit.id())).isPresent();
        assertThat(checkRepository.findHit(projectId, hit.id()).orElseThrow().basis())
                .contains("收盘价 12.10");
        assertThat(checkRepository.findHit(projectId + 1, hit.id())).isEmpty(); // 跨项目不可见
        assertThat(checkRepository.findHit(projectId, hit.id() + 1)).isEmpty(); // 不存在
    }

    @DisplayName("findChecks（P4-T3 复盘预填数据源）：createdAt 倒序 + 项目隔离（append-only 表开读端口）")
    @Test
    @Transactional
    void givenCheckRecords_whenFindChecks_thenOrderedDescAndScoped() {
        Long projectId = seedProject();
        List<CheckItemResult> items = List.of(new CheckItemResult("能力圈", null, null, CheckOutcome.PASS));
        CheckRecord first = checkRepository.insert(CheckRecord.create(projectId, CheckType.BUY,
                items, CheckResult.CONFIRMED, null));
        CheckRecord second = checkRepository.insert(CheckRecord.create(projectId, CheckType.SELL,
                items, CheckResult.OVERRIDDEN, "计划外机会"));

        List<CheckRecord> checks = checkRepository.findChecks(projectId);
        assertThat(checks).extracting(CheckRecord::id).containsExactly(second.id(), first.id()); // 倒序
        assertThat(checks.get(0).checkType()).isEqualTo(CheckType.SELL);
        assertThat(checks.get(0).overrideReason()).isEqualTo("计划外机会");
        assertThat(checks.get(1).result()).isEqualTo(CheckResult.CONFIRMED);
        assertThat(checkRepository.findChecks(projectId + 1)).isEmpty(); // 项目隔离
    }

    @DisplayName("证伪评审留痕：insert + findReviews 倒序 + attachReview 回填（hit 表唯一合法更新，首评占据软引用不覆盖）")
    @Test
    @Transactional
    void givenReviewAndHit_whenInsertAttach_thenReviewIdBackfilledAndNeverOverwritten() {
        Long projectId = seedProject();
        StrategyDoc strategy = projectRepository.saveStrategy(StrategyDoc.draftOf(projectId));
        projectRepository.saveFalsifiers(strategy.id(), List.of(
                Falsifier.ofPredicate(strategy.id(), FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.5"), "跌破下限")));
        Long falsifierId = projectRepository.findFalsifiers(strategy.id()).get(0).id();
        FalsifierHit hit = checkRepository.insertHit(new FalsifierHit(null, projectId, falsifierId,
                "收盘价 12.10 < 下限 13.5（东财收盘 2026-09-25）",
                java.time.Instant.parse("2026-09-25T10:00:00Z")));
        assertThat(checkRepository.findUnreviewedHitFalsifierIds(projectId)).containsExactly(falsifierId);

        FalsifierReview review = falsifierReviewRepository.insert(
                FalsifierReview.create(projectId, ReviewConclusion.REDUCE, "跌破下限，先减半仓"));
        assertThat(review.id()).isNotNull();
        falsifierReviewRepository.attachReview(hit.id(), review.id());

        // 回填落列：review_id 已指评审行；未评审集合清空（T5 去重口径传导）
        Long backfilled = jdbcTemplate.queryForObject(
                "SELECT review_id FROM research_falsifier_hit WHERE id = ?", Long.class, hit.id());
        assertThat(backfilled).isEqualTo(review.id());
        assertThat(checkRepository.findUnreviewedHitFalsifierIds(projectId)).isEmpty();

        // 二次评审：留痕照常追加（append-only），但软引用不覆盖（首评占据）
        FalsifierReview second = falsifierReviewRepository.insert(
                FalsifierReview.create(projectId, ReviewConclusion.REVISE, "证伪成立，逻辑需修订"));
        falsifierReviewRepository.attachReview(hit.id(), second.id());
        Long afterReattach = jdbcTemplate.queryForObject(
                "SELECT review_id FROM research_falsifier_hit WHERE id = ?", Long.class, hit.id());
        assertThat(afterReattach).isEqualTo(review.id());

        // findReviews：createdAt 倒序 + 字段保真；项目隔离
        List<FalsifierReview> reviews = falsifierReviewRepository.findReviews(projectId);
        assertThat(reviews).extracting(FalsifierReview::id)
                .containsExactly(second.id(), review.id());
        assertThat(reviews.get(1).conclusion()).isEqualTo(ReviewConclusion.REDUCE);
        assertThat(reviews.get(1).reason()).isEqualTo("跌破下限，先减半仓");
        assertThat(falsifierReviewRepository.findReviews(projectId + 1)).isEmpty();
    }
}
