package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AllocationPlanRepository;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.PlanSource;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
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
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest 切片 + 真实 PG：@ServiceConnection 复用 testFixtures 的 JVM 单例容器，整个测试进程只起一个 Postgres。
// Boot 4 的 @DataJpaTest 不含 Flyway 自动配置（schema 由 Flyway 管），需 @ImportAutoConfiguration 显式引入；
// RepositoryImpl 适配器不在切片扫描范围内，用 @Import 显式装配。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(AllocationPlanRepositoryImpl.class)
class AllocationPlanRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private AllocationPlanRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** allocation_plan.user_id 外键引用 app_user(id)，需先植入 id=42 的用户行以匹配测试内的 create(42L, ...)。 */
    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                42L, "alloc-t6-42", "h", "USER", "PENDING");
    }

    private static Map<AssetClass, BigDecimal> w60_40() {
        return Map.of(AssetClass.STOCK, new BigDecimal("60"), AssetClass.BOND, new BigDecimal("40"));
    }

    @DisplayName("保存方案并回读权重")
    @Test
    void whenSaveAndReadBack_thenWeightsPreserved() {
        AllocationPlan saved = repository.save(
                AllocationPlan.create(42L, "平衡", PlanSource.CUSTOM, w60_40(), Instant.now()));

        assertThat(saved.id()).isNotNull();
        var found = repository.findByIdAndUserId(saved.id(), 42L).orElseThrow();
        assertThat(found.name()).isEqualTo("平衡");
        assertThat(found.weights().get(AssetClass.STOCK)).isEqualByComparingTo("60");
        assertThat(found.weights().get(AssetClass.BOND)).isEqualByComparingTo("40");
    }

    @DisplayName("改权重后替换旧权重")
    @Test
    void givenSavedPlan_whenUpdateWeights_thenOldWeightsReplaced() {
        AllocationPlan saved = repository.save(
                AllocationPlan.create(42L, "平衡", PlanSource.CUSTOM, w60_40(), Instant.now()));
        repository.save(saved.updateWeights(Map.of(
                AssetClass.STOCK, new BigDecimal("40"), AssetClass.BOND, new BigDecimal("60"))));

        var found = repository.findByIdAndUserId(saved.id(), 42L).orElseThrow();
        assertThat(found.weights().get(AssetClass.STOCK)).isEqualByComparingTo("40");
        assertThat(found.weights().get(AssetClass.BOND)).isEqualByComparingTo("60");
        assertThat(found.weights()).hasSize(2);
    }

    @DisplayName("激活唯一性由用例层维护本层仅查询")
    @Test
    void givenTwoSavedPlans_whenFindByUserId_thenBothReturned() {
        repository.save(AllocationPlan.create(42L, "A", PlanSource.CUSTOM, w60_40(), Instant.now()));
        repository.save(AllocationPlan.create(42L, "B", PlanSource.CUSTOM, w60_40(), Instant.now()));
        // 本层不做唯一性约束；deactivateAllByUserId + 再 save(activate) 的编排在应用层（P2）验证。
        assertThat(repository.findByUserId(42L)).hasSize(2);
    }

    @DisplayName("删除方案级联删除权重")
    @Test
    void givenSavedPlan_whenDeleteById_thenCascadesWeights() {
        AllocationPlan saved = repository.save(
                AllocationPlan.create(42L, "平衡", PlanSource.CUSTOM, w60_40(), Instant.now()));
        repository.deleteById(saved.id());
        assertThat(repository.findByIdAndUserId(saved.id(), 42L)).isEmpty();
    }

    /**
     * 回归「重复激活已生效方案后 DB 仍唯一 active」。
     *
     * <p>复现应用层 activatePlan 的调用顺序：载入已生效方案 → 批量清空其余 →
     * 再 save 该方案。批量 UPDATE 缺 clearAutomatically 时，持久化上下文仍持有
     * 旧快照（active=true）；随后的 save 因脏检查认为无变化而不发 UPDATE，DB 被置为
     * 无生效方案。注意域方法 activate() 会顺带改 updatedAt 掩盖此缺陷，故这里直接
     * save 载入的原样快照以隔离「批量更新后上下文陈旧」这一根因。
     */
    @DisplayName("重复激活已生效方案数据库仍唯一生效")
    @Test
    void givenPlanAlreadyActive_whenActivateAgain_thenDbKeepsSingleActive() {
        AllocationPlan saved = repository.save(
                AllocationPlan.create(42L, "A", PlanSource.CUSTOM, w60_40(), Instant.now()));
        repository.save(saved.activate());
        AllocationPlan other = repository.save(
                AllocationPlan.create(42L, "B", PlanSource.CUSTOM, w60_40(), Instant.now()));
        assertThat(other.active()).isFalse();

        // 复现 service.activatePlan(42L, saved.id()) 的调用序列
        AllocationPlan loaded = repository.findByIdAndUserId(saved.id(), 42L).orElseThrow();
        assertThat(loaded.active()).isTrue();
        repository.deactivateAllByUserId(42L);
        repository.save(loaded);

        AllocationPlan active = repository.findActiveByUserId(42L).orElseThrow();
        assertThat(active.id()).isEqualTo(saved.id());
    }
}
