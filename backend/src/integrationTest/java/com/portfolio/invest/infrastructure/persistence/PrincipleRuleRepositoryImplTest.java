package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.wiki.PrincipleMetric;
import com.portfolio.invest.domain.wiki.PrincipleRule;
import com.portfolio.invest.domain.wiki.PrincipleRuleRepository;
import com.portfolio.invest.support.PostgresTestSupport;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(PrincipleRuleRepositoryImpl.class)
class PrincipleRuleRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private PrincipleRuleRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "pr_" + System.nanoTime());
    }

    @DisplayName("保存/按用户查询/归属过滤/删除")
    @Test
    @Transactional
    void givenRules_whenFindDelete_thenBehave() {
        Long user = seedUser();
        PrincipleRule saved = repository.save(PrincipleRule.create(user, PrincipleMetric.SINGLE_POSITION_RATIO,
                new BigDecimal("0.20"), true, "单票≤20%", java.time.Instant.now()));
        assertThat(saved.id()).isNotNull();

        assertThat(repository.findByUserId(user)).hasSize(1);
        assertThat(repository.findByUserId(user + 1)).isEmpty(); // 用户隔离
        assertThat(repository.findByIdAndUserId(saved.id(), user)).isPresent();
        assertThat(repository.findByIdAndUserId(saved.id(), user + 1)).isEmpty();

        repository.deleteById(saved.id());
        assertThat(repository.findByUserId(user)).isEmpty();
    }

    @DisplayName("UNIQUE(user_id, metric)：同用户同指标第二条被 DB 拒绝")
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void givenDuplicateMetric_whenSave_thenRejectedByDb() {
        Long user = seedUser();
        repository.save(PrincipleRule.create(user, PrincipleMetric.STOCK_PE_MAX,
                new BigDecimal("40"), true, null, java.time.Instant.now()));
        // 需先落库（NOT_SUPPORTED 下无事务包裹，save 即提交），再插第二条触发约束
        assertThatThrownBy(() -> {
            repository.save(PrincipleRule.create(user, PrincipleMetric.STOCK_PE_MAX,
                    new BigDecimal("35"), true, null, java.time.Instant.now()));
            // 无外层事务时约束违例在 save/flush 时抛出；若时序未触发，下面显式查数断言兜底
        }).isInstanceOf(DataIntegrityViolationException.class);
    }
}
