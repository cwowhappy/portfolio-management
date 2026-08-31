package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AllocationPlanRepository;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.PlanSource;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
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

    /** allocation_plan.user_id 外键引用 app_user(id)，需先植入 id=1 的用户行以匹配测试内的 create(1L, ...)。 */
    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                1L, "alloc-t6-1", "h", "USER", "PENDING");
    }

    private static Map<AssetClass, BigDecimal> w60_40() {
        return Map.of(AssetClass.STOCK, new BigDecimal("60"), AssetClass.BOND, new BigDecimal("40"));
    }

    @Test
    void 保存方案并回读权重() {
        AllocationPlan saved = repository.save(
                AllocationPlan.create(1L, "平衡", PlanSource.CUSTOM, w60_40(), Instant.now()));

        assertThat(saved.id()).isNotNull();
        var found = repository.findByIdAndUserId(saved.id(), 1L).orElseThrow();
        assertThat(found.name()).isEqualTo("平衡");
        assertThat(found.weights().get(AssetClass.STOCK)).isEqualByComparingTo("60");
        assertThat(found.weights().get(AssetClass.BOND)).isEqualByComparingTo("40");
    }

    @Test
    void 改权重后替换旧权重() {
        AllocationPlan saved = repository.save(
                AllocationPlan.create(1L, "平衡", PlanSource.CUSTOM, w60_40(), Instant.now()));
        repository.save(saved.updateWeights(Map.of(
                AssetClass.STOCK, new BigDecimal("40"), AssetClass.BOND, new BigDecimal("60"))));

        var found = repository.findByIdAndUserId(saved.id(), 1L).orElseThrow();
        assertThat(found.weights().get(AssetClass.STOCK)).isEqualByComparingTo("40");
        assertThat(found.weights().get(AssetClass.BOND)).isEqualByComparingTo("60");
        assertThat(found.weights()).hasSize(2);
    }

    @Test
    void 激活唯一性由用例层维护本层仅查询() {
        repository.save(AllocationPlan.create(1L, "A", PlanSource.CUSTOM, w60_40(), Instant.now()));
        repository.save(AllocationPlan.create(1L, "B", PlanSource.CUSTOM, w60_40(), Instant.now()));
        // 本层不做唯一性约束；deactivateAllByUserId + 再 save(activate) 的编排在应用层（P2）验证。
        assertThat(repository.findByUserId(1L)).hasSize(2);
    }

    @Test
    void 删除方案级联删除权重() {
        AllocationPlan saved = repository.save(
                AllocationPlan.create(1L, "平衡", PlanSource.CUSTOM, w60_40(), Instant.now()));
        repository.deleteById(saved.id());
        assertThat(repository.findByIdAndUserId(saved.id(), 1L)).isEmpty();
    }
}
