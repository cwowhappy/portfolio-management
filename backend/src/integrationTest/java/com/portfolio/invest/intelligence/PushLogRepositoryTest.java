package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.PushLog;
import com.portfolio.invest.domain.intelligence.PushLogRepository;
import com.portfolio.invest.domain.intelligence.PushStatus;
import com.portfolio.invest.domain.intelligence.PushType;
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
 * 推送留痕仓库真库契约（Testcontainers PG16 + V3 intelligence_push_log）：save 落三态行、
 * existsAnnouncementPush 幂等查重口径——OK/SKIPPED_NO_BINDING 行视为已推（跳过），
 * FAIL 行不算（失败允许下批重推），且只认 ANNOUNCEMENT 类型与该公告 ref。fixture 需
 * app_user 行（user_id 可空 FK，本仓留痕均带归属），照 SubscriptionRepositoryTest 先例；
 * @BeforeEach/@AfterEach 双向清空。附带锚定 SKIPPED 行 target 哨兵可落 NOT NULL 列。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PushLogRepositoryTest extends PostgresTestSupport {

    @Autowired
    PushLogRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM intelligence_push_log WHERE user_id IN"
                + " (SELECT id FROM app_user WHERE username LIKE 'plog_it%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'plog_it%'");
    }

    @Test
    @DisplayName("给定该用户该公告已有 OK 留痕，when幂等查重，then返回 true")
    void givenExistingOkRow_whenExistsAnnouncementPush_thenTrue() {
        Long userId = insertUser("plog_it_ok");
        repository.save(new PushLog(null, userId, PushType.ANNOUNCEMENT, "ou-x",
                "intelligence_announcement", 42L, PushStatus.OK, null, Instant.now()));

        assertThat(repository.existsAnnouncementPush(42L, userId)).isTrue();
    }

    @Test
    @DisplayName("给定该用户该公告仅有 FAIL 留痕，when幂等查重，then返回 false（失败允许重推）")
    void givenOnlyFailRow_whenExistsAnnouncementPush_thenFalse() {
        Long userId = insertUser("plog_it_fail");
        repository.save(new PushLog(null, userId, PushType.ANNOUNCEMENT, "ou-x",
                "intelligence_announcement", 42L, PushStatus.FAIL, "飞书单发返回失败", Instant.now()));

        assertThat(repository.existsAnnouncementPush(42L, userId)).isFalse();
    }

    @Test
    @DisplayName("给定无任何留痕，when幂等查重，then返回 false")
    void givenNoRows_whenExistsAnnouncementPush_thenFalse() {
        Long userId = insertUser("plog_it_none");

        assertThat(repository.existsAnnouncementPush(42L, userId)).isFalse();
    }

    @Test
    @DisplayName("给定 SKIPPED_NO_BINDING 留痕（target 哨兵占位 NOT NULL 列），when幂等查重，then同样视为已处理")
    void givenSkippedRowWithSentinelTarget_whenExistsAnnouncementPush_thenTrue() {
        Long userId = insertUser("plog_it_skip");
        repository.save(new PushLog(null, userId, PushType.ANNOUNCEMENT, "UNBOUND",
                "intelligence_announcement", 42L, PushStatus.SKIPPED_NO_BINDING, null, Instant.now()));

        assertThat(repository.existsAnnouncementPush(42L, userId)).isTrue();
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 照 SubscriptionRepositoryTest 的 insertUser 先例（app_user 为 push_log.user_id 的 FK 目标）。 */
    private Long insertUser(String username) {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
                + " VALUES(?, 'x', 'USER', 'APPROVED')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
}
