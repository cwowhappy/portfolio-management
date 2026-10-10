package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.memory.MemoryDigestCursor;
import com.portfolio.invest.domain.memory.MemoryDigestCursorRepository;
import com.portfolio.invest.domain.memory.UserProfileConfig;
import com.portfolio.invest.domain.memory.UserProfileConfigRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * MS-31 B1 两表真库契约（Testcontainers PG16 + V7 迁移，设计规格 §3.1/§4.1）：
 * user_profile_config 无行 = 全关（缺省 opt-in，决议 #3）、defaults 落库、update
 * 开关往返重读；memory_digest_cursor 由 V7 seed INSERT（id=1）迁移即存在、save
 * 单行覆盖不产生第二行。fixture 需 app_user 行（FK），照 SubscriptionRepositoryTest
 * 的 insertUser 先例；@BeforeEach/@AfterEach 双向清空（app_user 行数被兄弟类断言）。
 * 游标全局单行无 user 归属不清理（seed 语义，行恒在）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MemoryConfigRepositoriesIT extends PostgresTestSupport {

    @Autowired
    UserProfileConfigRepository profileConfigRepository;
    @Autowired
    MemoryDigestCursorRepository cursorRepository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        doCleanTables();
    }

    @AfterEach
    void cleanUpForSiblingClasses() {
        doCleanTables();
    }

    private void doCleanTables() {
        // user_profile_config 先于 app_user 删（FK）；memory_digest_cursor 全局单行保留
        jdbc.update("DELETE FROM user_profile_config WHERE user_id IN"
                + " (SELECT id FROM app_user WHERE username = 'mem_it_user')");
        jdbc.update("DELETE FROM app_user WHERE username = 'mem_it_user'");
    }

    @Test
    @DisplayName("config upsert：无行返回 empty；defaults 全关落库，update 开两开关后往返重读")
    void givenFreshUser_whenConfigUpsertRoundTrip_thenDefaultsAllOffAndTogglesRoundTrip() {
        Long uid = newUser();
        assertThat(profileConfigRepository.findByUserId(uid)).isEmpty();
        UserProfileConfig saved = profileConfigRepository.save(UserProfileConfig.defaults(uid));
        assertThat(saved.styleEnabled()).isFalse();
        UserProfileConfig on = saved.update(true, false, true, false);
        assertThat(profileConfigRepository.save(on).watchlistEnabled()).isTrue();
        assertThat(profileConfigRepository.findByUserId(uid).orElseThrow().styleEnabled()).isTrue();
    }

    @Test
    @DisplayName("cursor 单行：V7 seed（id=1）迁移即存在；save 覆盖单行不产生第二行")
    void givenSeededCursorRow_whenSaveCursor_thenSingleRowOverwritten() {
        assertThat(cursorRepository.find()).isPresent();
        MemoryDigestCursor c = cursorRepository.save(
                new MemoryDigestCursor(Instant.parse("2026-10-09T13:07:00Z")));
        assertThat(cursorRepository.find()).contains(c);
        assertThat(countCursorRows()).as("单行 upsert 覆盖，不产生第二行").isEqualTo(1);
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 照 SubscriptionRepositoryTest 的 insertUser 先例（app_user 为 user_profile_config 的 FK 目标）。 */
    private Long newUser() {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status, enabled)"
                + " VALUES('mem_it_user', 'x', 'USER', 'APPROVED', true)");
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username = 'mem_it_user'",
                Long.class);
    }

    private int countCursorRows() {
        return jdbc.queryForObject("SELECT count(*) FROM memory_digest_cursor", Integer.class);
    }
}
