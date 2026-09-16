package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.screening.WatchlistItem;
import com.portfolio.invest.domain.screening.WatchlistRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(WatchlistRepositoryImpl.class)
class WatchlistRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private WatchlistRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 造一个用户（app_user 列见 V1__init.sql），返回 id。 */
    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "wl_" + System.nanoTime());
    }

    @DisplayName("保存与按用户查询（addedAt 倒序）、存在性、计数、删除")
    @Test
    @Transactional
    void givenItems_whenFindExistsDelete_thenBehave() {
        Long user = seedUser();
        Instant early = Instant.parse("2026-09-15T00:00:00Z");
        Instant late = Instant.parse("2026-09-16T00:00:00Z");
        repository.save(new WatchlistItem(null, user, "600519", early));
        repository.save(new WatchlistItem(null, user, "601398", late));

        assertThat(repository.findByUserId(user))
                .extracting(WatchlistItem::stockCode).containsExactly("601398", "600519"); // addedAt 倒序
        assertThat(repository.existsByUserIdAndStockCode(user, "600519")).isTrue();
        assertThat(repository.existsByUserIdAndStockCode(user + 1, "600519")).isFalse(); // 用户隔离
        assertThat(repository.countByUserId(user)).isEqualTo(2);

        repository.deleteByUserIdAndStockCode(user, "600519");
        assertThat(repository.existsByUserIdAndStockCode(user, "600519")).isFalse();
        assertThat(repository.countByUserId(user)).isEqualTo(1);
    }
}
