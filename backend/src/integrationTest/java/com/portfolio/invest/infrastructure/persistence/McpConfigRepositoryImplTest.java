package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.mcp.AuthType;
import com.portfolio.invest.domain.mcp.McpEndpoint;
import com.portfolio.invest.domain.mcp.McpProvider;
import com.portfolio.invest.domain.mcp.McpUserConfig;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest 切片 + 真实 PG：继承 PostgresTestSupport 复用 testFixtures 的 JVM 单例容器（@DynamicPropertySource）。
// Boot 4 的 @DataJpaTest 不含 Flyway 自动配置（schema 由 Flyway 管），需 @ImportAutoConfiguration 显式引入；
// RepositoryImpl 适配器不在切片扫描范围内，用 @Import 显式装配。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(McpConfigRepositoryImpl.class)
class McpConfigRepositoryImplTest extends PostgresTestSupport {

    @Autowired
    private McpConfigRepositoryImpl repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** mcp_user_config.user_id 外键引用 app_user(id)，需先植入用户行；高位哨兵 id 避开全量运行时注册测试自动分配的 1..n。 */
    private static final long USER_61 = 61L;
    private static final long USER_62 = 62L;
    private static final long WIND_PROVIDER_ID = 3L;

    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                USER_61, "mcp-t6-61", "h", "USER", "PENDING");
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                USER_62, "mcp-t6-62", "h", "USER", "PENDING");
    }

    @DisplayName("迁移 seed 了 3 个启用的 provider")
    @Test
    void givenMigration_whenFindEnabledProviders_thenThreeSeededProvidersFound() {
        var providers = repository.findEnabledProviders();
        assertThat(providers).hasSize(3);
        assertThat(providers).extracting(McpProvider::code)
                .containsExactly("mx-ds", "tushare", "wind");
    }

    @DisplayName("wind 为 BEARER 且有 6 个 endpoint")
    @Test
    void givenWindProvider_whenFindEndpoints_thenBearerAuthWithSixEndpoints() {
        var wind = repository.findProviderById(WIND_PROVIDER_ID).orElseThrow();
        assertThat(wind.authType()).isEqualTo(AuthType.BEARER);
        var endpoints = repository.findEnabledEndpointsByProviderId(WIND_PROVIDER_ID);
        assertThat(endpoints).hasSize(6);
        assertThat(endpoints).extracting(McpEndpoint::domain)
                .containsExactlyInAnyOrder("stock", "fund", "index", "bond", "economic", "analytics");
    }

    @DisplayName("保存并回读用户配置")
    @Test
    void givenSavedConfig_whenReadBack_thenConfigMatches() {
        var saved = repository.save(McpUserConfig.create(USER_61, WIND_PROVIDER_ID, List.of("get_stock_quote"), Instant.now()));
        assertThat(saved.id()).isNotNull();
        var found = repository.findByUserIdAndProviderId(USER_61, WIND_PROVIDER_ID).orElseThrow();
        assertThat(found.disabledTools()).containsExactly("get_stock_quote");
        assertThat(found.configVersion()).isEqualTo(1);
    }

    @DisplayName("更新后 configVersion 自增")
    @Test
    void givenUpdate_whenReadBack_thenConfigVersionIncremented() {
        var saved = repository.save(McpUserConfig.create(USER_61, WIND_PROVIDER_ID, List.of(), Instant.now()));
        repository.save(saved.update(false, List.of("get_stock_kline"), Instant.now()));
        var found = repository.findByUserIdAndProviderId(USER_61, WIND_PROVIDER_ID).orElseThrow();
        assertThat(found.configVersion()).isEqualTo(2);
        assertThat(found.enabled()).isFalse();
    }

    @DisplayName("用户隔离与删除")
    @Test
    void givenTwoUsers_whenDeleteOneConfig_thenUserIsolationAndDeletion() {
        repository.save(McpUserConfig.create(USER_61, WIND_PROVIDER_ID, List.of(), Instant.now()));
        assertThat(repository.findByUserId(USER_62)).isEmpty();
        repository.deleteByUserIdAndProviderId(USER_61, WIND_PROVIDER_ID);
        assertThat(repository.findByUserId(USER_61)).isEmpty();
    }
}
