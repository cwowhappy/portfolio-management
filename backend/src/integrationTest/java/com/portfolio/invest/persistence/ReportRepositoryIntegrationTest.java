package com.portfolio.invest.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.research.ReportAnchor;
import com.portfolio.invest.domain.research.ReportAnchorRepository;
import com.portfolio.invest.domain.research.ReportAssumption;
import com.portfolio.invest.domain.research.ReportAssumptionRepository;
import com.portfolio.invest.domain.research.ReportTask;
import com.portfolio.invest.domain.research.ReportTaskRepository;
import com.portfolio.invest.domain.research.ReportTokenQuota;
import com.portfolio.invest.domain.research.ReportTokenQuotaRepository;
import com.portfolio.invest.domain.research.ResearchReport;
import com.portfolio.invest.domain.research.ResearchReportRepository;
import com.portfolio.invest.infrastructure.persistence.ReportAnchorRepositoryImpl;
import com.portfolio.invest.infrastructure.persistence.ReportAssumptionRepositoryImpl;
import com.portfolio.invest.infrastructure.persistence.ReportTaskRepositoryImpl;
import com.portfolio.invest.infrastructure.persistence.ReportTokenQuotaRepositoryImpl;
import com.portfolio.invest.infrastructure.persistence.ResearchReportRepositoryImpl;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * MS-32 B3 研报域 V8 迁移 + 五仓库集成测试（Testcontainers 真实 PG，设计规格 §二/§3.9）：
 * ①五表 + 部分唯一索引 uq_report_task_active 存在性与行为语义——并发双 insert 同 project
 * 恰一成功（R3），终态释放槽位后可再发起；②settle upsert 同日双调累计 + 跨日新行；
 * ③任务/研报/锚点/假设查询排序与往返（JSONB args 读写）。
 */
// @DataJpaTest 切片 + 真实 PG：@ServiceConnection 复用 testFixtures 的 JVM 单例容器（照
// ResearchProjectRepositoryImplTest 先例）；Boot 4 的 @DataJpaTest 不含 Flyway 自动配置需显式引入；
// 仓库适配器不在切片扫描范围内，用 @Import 显式装配。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import({ReportTaskRepositoryImpl.class, ResearchReportRepositoryImpl.class,
        ReportAnchorRepositoryImpl.class, ReportAssumptionRepositoryImpl.class,
        ReportTokenQuotaRepositoryImpl.class})
class ReportRepositoryIntegrationTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private ReportTaskRepository taskRepository;
    @Autowired
    private ResearchReportRepository reportRepository;
    @Autowired
    private ReportAnchorRepository anchorRepository;
    @Autowired
    private ReportAssumptionRepository assumptionRepository;
    @Autowired
    private ReportTokenQuotaRepository quotaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "report_b3_" + System.nanoTime());
    }

    private Long seedProject(Long userId, String title) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO research_project(user_id, stock_code, stock_name, title, current_stage) "
                        + "VALUES (?, '600519', '贵州茅台', ?, 'NEW_ANALYSIS') RETURNING id",
                Long.class, userId, title);
    }

    @DisplayName("V8 五表与部分唯一索引存在：uq_report_task_active 只圈 PENDING/RUNNING")
    @Test
    @Transactional
    void givenV8Migrated_whenInspectSchema_thenFiveTablesAndPartialUniqueIndexExist() {
        for (String table : List.of("research_report_task", "research_report", "research_report_anchor",
                "research_report_assumption", "report_token_quota")) {
            assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('public." + table + "')", String.class))
                    .as("V8 应建表 %s", table).isNotNull();
        }
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'uq_report_task_active'",
                String.class);
        assertThat(indexDef)
                .contains("UNIQUE INDEX")      // 唯一性：同 project 第二个活跃任务必撞
                .contains("research_report_task") // 锚定任务表
                .contains("PENDING").contains("RUNNING"); // 部分索引谓词只圈活跃态
    }

    /**
     * R3 并发恰一：两线程对同 project 并发 saveAndFlush 活跃任务（CountDownLatch 同步起跑），
     * 断言恰一成功、另一笔 DataIntegrityViolationException（部分唯一索引在 DB 层兜底）。
     * NOT_SUPPORTED 关闭测试托管事务：种子须为提交态，两个并发事务才可见（照
     * PortfolioRepositoryImplTest.whenLockSameGroupInTwoTransactions 先例）；本用例数据
     * 不走事务回滚，finally 手动清理共享容器。
     */
    @DisplayName("并发双发起同项目活跃任务：恰一成功，另一笔撞部分唯一索引；终态释放槽位后可再发起")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void givenCommittedProject_whenTwoThreadsInsertActiveTaskConcurrently_thenExactlyOneSucceeds() throws Exception {
        Long user = seedUser();          // NOT_SUPPORTED 下 jdbcTemplate 即时提交
        Long project = seedProject(user, "并发恰一");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch startGate = new CountDownLatch(1);
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            Callable<Optional<Throwable>> worker = () -> {
                startGate.await();
                try {
                    tx.executeWithoutResult(s ->
                            taskRepository.save(ReportTask.create(user, project, Instant.now())));
                    return Optional.empty();
                } catch (RuntimeException e) {
                    return Optional.of(e);
                }
            };
            Future<Optional<Throwable>> first = pool.submit(worker);
            Future<Optional<Throwable>> second = pool.submit(worker);
            startGate.countDown();
            List<Optional<Throwable>> outcomes = List.of(first.get(20, TimeUnit.SECONDS),
                    second.get(20, TimeUnit.SECONDS));

            long succeeded = outcomes.stream().filter(Optional::isEmpty).count();
            List<Throwable> rejected = outcomes.stream().flatMap(Optional::stream).toList();
            assertThat(succeeded).as("两笔并发活跃任务恰一成功").isEqualTo(1);
            assertThat(rejected).as("另一笔被拒").hasSize(1);
            assertThat(rejected.get(0)).isInstanceOf(DataIntegrityViolationException.class);

            Integer activeCount = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM research_report_task WHERE project_id = ? AND status IN ('PENDING','RUNNING')",
                    Integer.class, project);
            assertThat(activeCount).as("库内同项目活跃任务恰一行").isEqualTo(1);

            // 部分索引语义（行为级）：终态不占索引位——胜者收尾 FAILED 后同项目可再发起新任务
            ReportTask winner = taskRepository.findLatestByProjectId(project).orElseThrow();
            taskRepository.save(winner.fail("AGENT_ERROR", "探针终态化", 0, Instant.now()));
            ReportTask retried = taskRepository.save(ReportTask.create(user, project, Instant.now()));
            assertThat(retried.status()).isEqualTo(ReportTask.Status.PENDING);
            ReportTask latest = taskRepository.findLatestByProjectId(project).orElseThrow();
            assertThat(latest.id()).isEqualTo(retried.id()); // ReportTask 无值 equals，按 id 锚定
            assertThat(latest.status()).isEqualTo(ReportTask.Status.PENDING);
        } finally {
            pool.shutdownNow();
            // 手动清理（本用例数据不走事务回滚）：子表 → 项目 → 用户
            jdbcTemplate.update("DELETE FROM research_report_task WHERE project_id = ?", project);
            jdbcTemplate.update("DELETE FROM research_project WHERE id = ?", project);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user);
        }
    }

    @DisplayName("settle 同日双调累计、跨日新行、无行/他人为空")
    @Test
    @Transactional
    void givenQuotaSettleTwiceSameDayOnceNextDay_whenFindByDate_thenAccumulatesPerDay() {
        Long user = seedUser();
        LocalDate today = LocalDate.of(2026, 10, 11);
        LocalDate nextDay = today.plusDays(1);

        quotaRepository.settle(user, today, 100);
        quotaRepository.settle(user, today, 250);   // 同日第二笔 upsert 累加，不丢增量
        quotaRepository.settle(user, nextDay, 50);  // 跨日另起一行

        assertThat(quotaRepository.findByUserIdAndDate(user, today))
                .contains(new ReportTokenQuota(user, today, 350));
        assertThat(quotaRepository.findByUserIdAndDate(user, nextDay))
                .contains(new ReportTokenQuota(user, nextDay, 50));
        assertThat(quotaRepository.findByUserIdAndDate(user, today.plusDays(2))).isEmpty(); // 无行为空
        assertThat(quotaRepository.findByUserIdAndDate(user + 1, today)).isEmpty();         // 他人隔离
    }

    @DisplayName("任务查询：findLatestByProjectId 取最新、findActiveAll 只圈 PENDING/RUNNING 且 id 升序")
    @Test
    @Transactional
    void givenTasksAcrossStates_whenFindLatestAndActive_thenLatestWinsAndActiveFiltered() {
        Long user = seedUser();
        Long projectP = seedProject(user, "项目P");
        Long projectQ = seedProject(user, "项目Q");

        // P：任务一走完生命周期至 SUCCEEDED（终态释放单活跃槽位），任务二再 PENDING
        ReportTask taskP1 = taskRepository.save(ReportTask.create(user, projectP, Instant.now()));
        taskRepository.save(taskP1.markRunning(Instant.now()).succeed(1200, Instant.now()));
        ReportTask taskP2 = taskRepository.save(ReportTask.create(user, projectP, Instant.now()));
        // Q：任务三 RUNNING（跨项目活跃）
        ReportTask taskQ3 = taskRepository.save(
                ReportTask.create(user, projectQ, Instant.now()).markRunning(Instant.now()));

        ReportTask latestP = taskRepository.findLatestByProjectId(projectP).orElseThrow();
        assertThat(latestP.id()).isEqualTo(taskP2.id()); // id 最大者；ReportTask 无值 equals，按 id 锚定
        assertThat(latestP.status()).isEqualTo(ReportTask.Status.PENDING);
        assertThat(taskRepository.findById(taskP2.id()))
                .hasValueSatisfying(found -> assertThat(found.id()).isEqualTo(taskP2.id()));
        assertThat(taskRepository.findActiveAll())
                .extracting(ReportTask::id)
                .containsExactly(taskP2.id(), taskQ3.id()) // 活跃全量（id 升序），SUCCEEDED 不进
                .doesNotContain(taskP1.id());
    }

    @DisplayName("研报/锚点/假设往返：JSONB args 读写、createdAt 倒序、seq 升序、confirm 落 confirmedAt")
    @Test
    @Transactional
    void givenFinalizedReport_whenSaveAnchorsAssumptionsAndConfirm_thenRoundTripOrdered() {
        Long user = seedUser();
        Long project = seedProject(user, "研报往返");
        ReportTask task1 = taskRepository.save(
                ReportTask.create(user, project, Instant.now())
                        .markRunning(Instant.now()).succeed(800, Instant.now()));
        ReportTask task2 = taskRepository.save(
                ReportTask.create(user, project, Instant.now())
                        .markRunning(Instant.now()).succeed(900, Instant.now()));

        Instant t0 = Instant.parse("2026-10-11T01:00:00Z");
        Instant t1 = Instant.parse("2026-10-11T02:00:00Z");
        ResearchReport report1 = reportRepository.save(ResearchReport.create(
                task1.id(), project, user, "茅台一期研报", "定稿正文 ⚠ 校验修正注记", "行情接口部分失败", t0));
        ResearchReport report2 = reportRepository.save(ResearchReport.create(
                task2.id(), project, user, "茅台二期研报", "二期定稿", null, t1));
        assertThat(report1.id()).isNotNull();

        // 项目研报列表：createdAt 倒序（最新定稿在前）；limitations 可空往返
        List<ResearchReport> byProject = reportRepository.findByProjectIdOrderByCreatedAtDesc(project);
        assertThat(byProject).extracting(ResearchReport::title)
                .containsExactly("茅台二期研报", "茅台一期研报");
        assertThat(byProject.get(1).limitations()).isEqualTo("行情接口部分失败");
        assertThat(byProject.get(0).limitations()).isNull();

        // 锚点：VERIFIED 带工具上下文 + SOURCED 全空上下文（可空往返），id 升序（落库序）
        Instant asOf = Instant.parse("2026-10-10T15:00:00Z");
        anchorRepository.saveAll(report1.id(), List.of(
                ReportAnchor.of(report1.id(), "2580", 1, "VERIFIED", "fetchStockQuote",
                        Map.of("code", "600519"), asOf, "DATA", "2580.00 元"),
                ReportAnchor.of(report1.id(), "12.5", 2, "SOURCED", null, null, null, null, null)));
        List<ReportAnchor> anchors = anchorRepository.findByReportId(report1.id());
        assertThat(anchors).hasSize(2);
        assertThat(anchors.get(0).state()).isEqualTo("VERIFIED");
        assertThat(anchors.get(0).occ()).isEqualTo(1);
        assertThat(anchors.get(0).args()).isEqualTo(Map.of("code", "600519")); // JSONB Map 读写保真
        assertThat(anchors.get(0).asOf()).isEqualTo(asOf);
        assertThat(anchors.get(0).raw()).isEqualTo("2580.00 元");
        assertThat(anchors.get(1).toolName()).isNull(); // 可空字段往返保真
        assertThat(anchors.get(1).args()).isNull();
        assertThat(anchorRepository.findByReportId(report2.id())).isEmpty(); // 研报隔离

        // 假设：seq 升序、anchorId 绑定/未绑定（null）两态
        assumptionRepository.saveAll(report1.id(), List.of(
                new ReportAssumption(null, report1.id(), 1, "批价中枢维持 2580", anchors.get(0).id()),
                new ReportAssumption(null, report1.id(), 2, "未绑定门假设", null)));
        List<ReportAssumption> assumptions = assumptionRepository.findByReportId(report1.id());
        assertThat(assumptions).extracting(ReportAssumption::seq).containsExactly(1, 2);
        assertThat(assumptions.get(0).anchorId()).isEqualTo(anchors.get(0).id());
        assertThat(assumptions.get(1).anchorId()).isNull();

        // 确认定稿：PENDING_CONFIRM→CONFIRMED 落 confirmedAt
        Instant confirmedAt = Instant.parse("2026-10-11T03:00:00Z");
        ResearchReport confirmed = reportRepository.save(
                reportRepository.findById(report1.id()).orElseThrow().confirm(confirmedAt).orElseThrow());
        ResearchReport reloaded = reportRepository.findById(report1.id()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo(ResearchReport.Status.CONFIRMED);
        assertThat(reloaded.confirmedAt()).isEqualTo(confirmedAt);
        assertThat(confirmed.status()).isEqualTo(ResearchReport.Status.CONFIRMED);
    }
}
