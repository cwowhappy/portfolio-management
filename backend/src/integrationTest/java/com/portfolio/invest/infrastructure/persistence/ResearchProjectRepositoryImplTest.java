package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.research.Falsifier;
import com.portfolio.invest.domain.research.FalsifierKind;
import com.portfolio.invest.domain.research.FalsifierPredicate;
import com.portfolio.invest.domain.research.ProjectStatus;
import com.portfolio.invest.domain.research.ResearchProject;
import com.portfolio.invest.domain.research.ResearchProjectRepository;
import com.portfolio.invest.domain.research.ResearchStage;
import com.portfolio.invest.domain.research.StageCompletionService.ManualState;
import com.portfolio.invest.domain.research.StrategyDoc;
import com.portfolio.invest.domain.research.StrategyState;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(ResearchProjectRepositoryImpl.class)
class ResearchProjectRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private ResearchProjectRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "research_" + System.nanoTime());
    }

    @DisplayName("项目保存往返（含可空 industryCode 与枚举）+ 域操作回存 version 递增")
    @Test
    @Transactional
    void givenProject_whenSaveFind_thenRoundTrip() {
        Long user = seedUser();
        ResearchProject saved = repository.save(ResearchProject.create(
                user, "600519", "贵州茅台", null, "茅台新分析", ResearchStage.NEW_ANALYSIS));

        assertThat(saved.id()).isNotNull();
        assertThat(saved.version()).isEqualTo(0L);
        assertThat(saved.createdAt()).isNotNull();
        assertThat(saved.updatedAt()).isNotNull();

        ResearchProject found = repository.findById(saved.id()).orElseThrow();
        assertThat(found.userId()).isEqualTo(user);
        assertThat(found.stockCode()).isEqualTo("600519");
        assertThat(found.stockName()).isEqualTo("贵州茅台");
        assertThat(found.industryCode()).isNull(); // 可空字段往返保真
        assertThat(found.title()).isEqualTo("茅台新分析");
        assertThat(found.currentStage()).isEqualTo(ResearchStage.NEW_ANALYSIS);
        assertThat(found.status()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(found.intelligenceAlertEnabled()).isTrue(); // V3 列默认 TRUE（决策 #26）
        assertThat(found.version()).isEqualTo(0L);
        assertThat(found.createdAt()).isEqualTo(saved.createdAt());
        assertThat(found.updatedAt()).isEqualTo(saved.updatedAt());

        // 域操作（阶段跳转 + 归档）回存：version 乐观锁递增
        ResearchProject updated = repository.save(found.changeStage(ResearchStage.STRATEGY).archive());
        assertThat(updated.version()).isEqualTo(1L);
        ResearchProject reloaded = repository.findById(saved.id()).orElseThrow();
        assertThat(reloaded.currentStage()).isEqualTo(ResearchStage.STRATEGY);
        assertThat(reloaded.status()).isEqualTo(ProjectStatus.ARCHIVED);
        assertThat(reloaded.version()).isEqualTo(1L);

        // 情报提醒开关往返（M16-F11）：wither 关闭回存、重载为 FALSE、再开恢复
        ResearchProject disabled = repository.save(reloaded.withIntelligenceAlert(false));
        assertThat(disabled.intelligenceAlertEnabled()).isFalse();
        assertThat(repository.findById(saved.id()).orElseThrow().intelligenceAlertEnabled()).isFalse();
        Boolean columnValue = jdbcTemplate.queryForObject(
                "SELECT intelligence_alert_enabled FROM research_project WHERE id = ?",
                Boolean.class, saved.id());
        assertThat(columnValue).isFalse(); // 列级落库（持仓情报挂接 SQL 消费口径）
        assertThat(repository.save(disabled.withIntelligenceAlert(true)).intelligenceAlertEnabled()).isTrue();
    }

    @DisplayName("按用户与状态查询：updatedAt 倒序、状态过滤、用户隔离")
    @Test
    @Transactional
    void givenProjects_whenFindByUserId_thenFilteredOrderedAndIsolated() {
        Long user = seedUser();
        ResearchProject p1 = repository.save(ResearchProject.create(
                user, "600519", "贵州茅台", "801150", "茅台", ResearchStage.NEW_ANALYSIS));
        ResearchProject p2 = repository.save(ResearchProject.create(
                user, "000858", "五粮液", null, "五粮液", ResearchStage.STRATEGY));
        repository.save(p2.rename("五粮液（修订）")); // 抬高 p2.updatedAt 保证倒序确定

        assertThat(repository.findByUserId(user, null))
                .extracting(ResearchProject::title).containsExactly("五粮液（修订）", "茅台"); // updatedAt 倒序
        assertThat(repository.findByUserId(user, ProjectStatus.ACTIVE)).hasSize(2);
        assertThat(repository.findByUserId(user, ProjectStatus.ARCHIVED)).isEmpty();
        assertThat(repository.findByUserId(user + 1, null)).isEmpty(); // 用户隔离

        repository.save(p1.archive());
        assertThat(repository.findByUserId(user, ProjectStatus.ARCHIVED))
                .extracting(ResearchProject::title).containsExactly("茅台");
        assertThat(repository.findByUserId(user, ProjectStatus.ACTIVE))
                .extracting(ResearchProject::title).containsExactly("五粮液（修订）");
    }

    @DisplayName("全用户 ACTIVE+指定阶段取数（T5 日终扫描口）：非 ACTIVE / 非该阶段项目不进结果")
    @Test
    @Transactional
    void givenMixedProjects_whenFindAllActiveByStage_thenOnlyActivePositionReturned() {
        Long user = seedUser();
        ResearchProject position = repository.save(ResearchProject.create(
                user, "600519", "贵州茅台", null, "茅台持仓中", ResearchStage.POSITION));
        repository.save(ResearchProject.create(
                user, "000858", "五粮液", null, "五粮液策略期", ResearchStage.STRATEGY)); // 阶段不符
        ResearchProject archived = repository.save(ResearchProject.create(
                user, "601318", "中国平安", null, "平安已归档", ResearchStage.POSITION));
        repository.save(archived.archive()); // 状态不符

        assertThat(repository.findAllActiveByStage(ResearchStage.POSITION))
                .extracting(ResearchProject::id)
                .containsExactly(position.id()); // 只剩 ACTIVE+POSITION 一行
        assertThat(repository.findAllActiveByStage(ResearchStage.STRATEGY))
                .extracting(ResearchProject::title).containsExactly("五粮液策略期");
    }

    @DisplayName("手动完成度覆盖：upsert 不撞唯一约束、覆盖生效、NULL 清除不进 Map")
    @Test
    @Transactional
    void givenProject_whenSaveManualState_thenUpsertAndNullSemantics() {
        Long user = seedUser();
        ResearchProject project = repository.save(ResearchProject.create(
                user, "600519", "贵州茅台", null, "茅台", ResearchStage.NEW_ANALYSIS));

        assertThat(repository.findManualStates(project.id())).isEmpty(); // 无任何覆盖

        repository.saveManualState(project.id(), ResearchStage.NEW_ANALYSIS, ManualState.COMPLETED);
        repository.saveManualState(project.id(), ResearchStage.STRATEGY, ManualState.REOPENED);
        assertThat(repository.findManualStates(project.id()))
                .containsOnlyKeys(ResearchStage.NEW_ANALYSIS, ResearchStage.STRATEGY)
                .containsEntry(ResearchStage.NEW_ANALYSIS, ManualState.COMPLETED)
                .containsEntry(ResearchStage.STRATEGY, ManualState.REOPENED);

        // 同 (project_id, stage) 二次写为 UPDATE，不撞 uk_research_stage
        repository.saveManualState(project.id(), ResearchStage.NEW_ANALYSIS, ManualState.REOPENED);
        assertThat(repository.findManualStates(project.id()))
                .containsEntry(ResearchStage.NEW_ANALYSIS, ManualState.REOPENED)
                .hasSize(2);

        // ManualState.NULL = 清除覆盖：行保留但 manual_state 置空，findManualStates 缺项即无覆盖（S6）
        repository.saveManualState(project.id(), ResearchStage.NEW_ANALYSIS, ManualState.NULL);
        assertThat(repository.findManualStates(project.id()))
                .containsOnlyKeys(ResearchStage.STRATEGY);

        assertThat(repository.findManualStates(project.id() + 1)).isEmpty(); // 项目隔离
    }

    @DisplayName("策略文档两级状态往返：可空草稿字段/BigDecimal 值保真、定稿、修订回草稿")
    @Test
    @Transactional
    void givenProject_whenSaveStrategy_thenTwoStateRoundTrip() {
        Long user = seedUser();
        ResearchProject project = repository.save(ResearchProject.create(
                user, "600519", "贵州茅台", null, "茅台", ResearchStage.STRATEGY));

        assertThat(repository.findStrategy(project.id())).isEmpty();

        // 新建草稿：六字段全空可存（D13）
        StrategyDoc saved = repository.saveStrategy(StrategyDoc.draftOf(project.id()));
        assertThat(saved.id()).isNotNull();
        StrategyDoc emptyDraft = repository.findStrategy(project.id()).orElseThrow();
        assertThat(emptyDraft.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(emptyDraft.thesis()).isNull();
        assertThat(emptyDraft.valuationLow()).isNull();
        assertThat(emptyDraft.finalizedAt()).isNull();

        // 暂存六字段回读：NUMERIC(12,4) 值保真（isEqualByComparingTo，不锁 scale）
        repository.saveStrategy(emptyDraft.saveDraft("高端白酒提价能力强", new BigDecimal("12.50"),
                new BigDecimal("15.00"), "首仓 10%", "回踩 13 元附近分批", "政策风险"));
        StrategyDoc draft = repository.findStrategy(project.id()).orElseThrow();
        assertThat(draft.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(draft.thesis()).isEqualTo("高端白酒提价能力强");
        assertThat(draft.valuationLow()).isEqualByComparingTo("12.5");
        assertThat(draft.valuationHigh()).isEqualByComparingTo("15");
        assertThat(draft.positionPlan()).isEqualTo("首仓 10%");
        assertThat(draft.buyConditions()).isEqualTo("回踩 13 元附近分批");
        assertThat(draft.riskNotes()).isEqualTo("政策风险");
        assertThat(draft.finalizedAt()).isNull();

        // 定稿：FINALIZED + finalizedAt 落库
        repository.saveStrategy(draft.finalizeDoc());
        StrategyDoc finalized = repository.findStrategy(project.id()).orElseThrow();
        assertThat(finalized.state()).isEqualTo(StrategyState.FINALIZED);
        assertThat(finalized.finalizedAt()).isNotNull();

        // 修订：回 DRAFT、清 finalizedAt，内容字段保留待覆盖（D13 覆盖式）
        repository.saveStrategy(finalized.revise());
        StrategyDoc revised = repository.findStrategy(project.id()).orElseThrow();
        assertThat(revised.state()).isEqualTo(StrategyState.DRAFT);
        assertThat(revised.finalizedAt()).isNull();
        assertThat(revised.thesis()).isEqualTo("高端白酒提价能力强");
    }

    @DisplayName("同项目二度新建策略文档：UNIQUE(project_id) 事务内早抛（saveAndFlush）")
    @Test
    @Transactional
    void givenExistingStrategy_whenSaveAnotherDraft_thenUniqueViolation() {
        Long user = seedUser();
        ResearchProject project = repository.save(ResearchProject.create(
                user, "600519", "贵州茅台", null, "茅台", ResearchStage.STRATEGY));
        repository.saveStrategy(StrategyDoc.draftOf(project.id()));

        // 同项目再 draftOf（id=null 视作 INSERT）→ 唯一约束违例在事务内尽早抛出
        assertThatThrownBy(() -> repository.saveStrategy(StrategyDoc.draftOf(project.id())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @DisplayName("证伪条件整替：两类字段/可空往返、旧集清空、未知策略为空")
    @Test
    @Transactional
    void givenStrategy_whenSaveFalsifiers_thenReplaceAllRoundTrip() {
        Long user = seedUser();
        ResearchProject project = repository.save(ResearchProject.create(
                user, "600519", "贵州茅台", null, "茅台", ResearchStage.STRATEGY));
        StrategyDoc strategy = repository.saveStrategy(StrategyDoc.draftOf(project.id()));

        assertThat(repository.findFalsifiers(strategy.id())).isEmpty();

        repository.saveFalsifiers(strategy.id(), List.of(
                Falsifier.ofPredicate(strategy.id(), FalsifierPredicate.PRICE_BELOW,
                        new BigDecimal("13.0000"), "跌破估值下限"),
                Falsifier.ofEvent(strategy.id(), "季度批价环比转负")));

        List<Falsifier> found = repository.findFalsifiers(strategy.id());
        assertThat(found).hasSize(2);
        Falsifier predicate = found.get(0);
        assertThat(predicate.id()).isNotNull();
        assertThat(predicate.kind()).isEqualTo(FalsifierKind.PREDICATE);
        assertThat(predicate.predicate()).isEqualTo(FalsifierPredicate.PRICE_BELOW);
        assertThat(predicate.threshold()).isEqualByComparingTo("13");
        assertThat(predicate.eventChecked()).isFalse();
        assertThat(predicate.note()).isEqualTo("跌破估值下限");
        assertThat(predicate.enabled()).isTrue();
        Falsifier event = found.get(1);
        assertThat(event.kind()).isEqualTo(FalsifierKind.EVENT);
        assertThat(event.predicate()).isNull(); // 可空字段往返保真
        assertThat(event.threshold()).isNull();
        assertThat(event.note()).isEqualTo("季度批价环比转负");

        // 整替：旧两条清空，只剩新一条
        repository.saveFalsifiers(strategy.id(), List.of(
                Falsifier.ofEvent(strategy.id(), "茅台批价连续两周下行")));
        List<Falsifier> replaced = repository.findFalsifiers(strategy.id());
        assertThat(replaced).hasSize(1);
        assertThat(replaced.get(0).kind()).isEqualTo(FalsifierKind.EVENT);
        assertThat(replaced.get(0).note()).isEqualTo("茅台批价连续两周下行");

        assertThat(repository.findFalsifiers(strategy.id() + 1)).isEmpty(); // 策略隔离
    }
}
