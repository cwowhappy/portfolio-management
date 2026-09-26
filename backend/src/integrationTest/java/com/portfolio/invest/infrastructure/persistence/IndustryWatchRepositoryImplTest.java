package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.IndustryWatchItem;
import com.portfolio.invest.domain.industry.IndustryWatchRepository;
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
import static org.assertj.core.api.Assertions.assertThatCode;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(IndustryWatchRepositoryImpl.class)
class IndustryWatchRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private IndustryWatchRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 造一个用户（app_user 列见 V1__init.sql），返回 id。 */
    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "iw_" + System.nanoTime());
    }

    @DisplayName("保存与按用户查询：命中关注项且 addedAt 倒序")
    @Test
    @Transactional
    void givenWatchedIndustry_whenSaveAndFindByUserId_thenReturnItem() {
        Long user = seedUser();
        repository.save(new IndustryWatchItem(null, user, "801780", Instant.parse("2026-09-15T00:00:00Z")));
        repository.save(new IndustryWatchItem(null, user, "801010", Instant.parse("2026-09-16T00:00:00Z")));

        var items = repository.findByUserId(user);
        assertThat(items).extracting(IndustryWatchItem::industryCode)
                .containsExactly("801010", "801780"); // addedAt 倒序
        assertThat(items.get(0).userId()).isEqualTo(user);
        assertThat(items.get(0).addedAt()).isEqualTo(Instant.parse("2026-09-16T00:00:00Z"));
    }

    @DisplayName("重复关注同行业幂等：二次 save 不抛且仍只一行（先查后插，照 Watchlist 先例）")
    @Test
    @Transactional
    void givenAlreadyWatched_whenSaveAgain_thenNoThrowAndExistsTrue() {
        Long user = seedUser();
        repository.save(new IndustryWatchItem(null, user, "801780", Instant.now()));

        assertThatCode(() -> repository.save(new IndustryWatchItem(null, user, "801780", Instant.now())))
                .doesNotThrowAnyException(); // UNIQUE(user_id, industry_code) 冲突不外抛

        assertThat(repository.existsByUserIdAndIndustryCode(user, "801780")).isTrue();
        assertThat(repository.existsByUserIdAndIndustryCode(user + 1, "801780")).isFalse(); // 用户隔离
        assertThat(repository.findByUserId(user)).hasSize(1); // 不产生重复行
    }

    @DisplayName("取关后存在性翻 false 且列表清空")
    @Test
    @Transactional
    void givenWatchedIndustry_whenDelete_thenExistsFalse() {
        Long user = seedUser();
        repository.save(new IndustryWatchItem(null, user, "801780", Instant.now()));

        repository.deleteByUserIdAndIndustryCode(user, "801780");

        assertThat(repository.existsByUserIdAndIndustryCode(user, "801780")).isFalse();
        assertThat(repository.findByUserId(user)).isEmpty();
    }

    @DisplayName("取关不存在的行不抛（幂等删除）")
    @Test
    @Transactional
    void givenNeverWatchedIndustry_whenDelete_thenNoThrow() {
        Long user = seedUser();

        assertThatCode(() -> repository.deleteByUserIdAndIndustryCode(user, "999999"))
                .doesNotThrowAnyException();
        assertThat(repository.existsByUserIdAndIndustryCode(user, "999999")).isFalse();
    }
}
