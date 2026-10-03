package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.FeishuBindingRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 飞书绑定仓库真库契约（Testcontainers PG16 + V3 intelligence_feishu_binding，D8）：
 * findOpenIdByUserId 已绑定返回 open_id、未绑定返回 empty——公告定向推送（Task 7）以
 * empty 判 SKIPPED_NO_BINDING，P4 绑定流程就位后自然生效。fixture 需 app_user 行（FK），
 * 照 SubscriptionRepositoryTest 的 insertUser 先例；@BeforeEach/@AfterEach 双向清空。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FeishuBindingRepositoryTest extends PostgresTestSupport {

    @Autowired
    FeishuBindingRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM intelligence_feishu_binding WHERE user_id IN"
                + " (SELECT id FROM app_user WHERE username LIKE 'bind_it%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'bind_it%'");
    }

    @Test
    @DisplayName("给定用户已绑定飞书，when按 userId 查 open_id，then返回绑定值")
    void givenBoundUser_whenFindOpenIdByUserId_thenReturnsOpenId() {
        Long userId = insertUser("bind_it_ok");
        jdbc.update("INSERT INTO intelligence_feishu_binding(user_id, open_id) VALUES(?, 'ou-abc')",
                userId);

        assertThat(repository.findOpenIdByUserId(userId)).contains("ou-abc");
    }

    @Test
    @DisplayName("给定用户未绑定，when按 userId 查 open_id，then返回 empty（推送侧记 SKIPPED_NO_BINDING）")
    void givenUnboundUser_whenFindOpenIdByUserId_thenEmpty() {
        Long userId = insertUser("bind_it_none");

        assertThat(repository.findOpenIdByUserId(userId)).isEmpty();
    }

    @Test
    @DisplayName("给定已绑定用户，when按 userId 解绑，then删除绑定行并返回 true（open_id 随行释放）")
    void givenBoundUser_whenDeleteByUserId_thenRowDeletedAndTrue() {
        Long userId = insertUser("bind_it_del");
        jdbc.update("INSERT INTO intelligence_feishu_binding(user_id, open_id) VALUES(?, 'ou-del')",
                userId);

        boolean deleted = repository.deleteByUserId(userId);

        assertThat(deleted).isTrue();
        assertThat(repository.findOpenIdByUserId(userId)).as("解绑后立即可见——旧 open_id 不再收推送").isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_feishu_binding WHERE user_id = ?",
                Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("给定未绑定用户，when按 userId 解绑，then无行可删返回 false（幂等语义）")
    void givenUnboundUser_whenDeleteByUserId_thenFalse() {
        Long userId = insertUser("bind_it_del_none");

        assertThat(repository.deleteByUserId(userId)).isFalse();
    }

    // ── findBoundAtByUserId（P4 Task 6：设置页绑定态查询）──────────

    @Test
    @DisplayName("给定已绑定用户，when按 userId 查绑定时刻，then返回 bound_at 原值（UTC 折算无损）")
    void givenBoundUser_whenFindBoundAtByUserId_thenReturnsBoundAt() {
        Long userId = insertUser("bind_it_at");
        Instant boundAt = Instant.parse("2026-10-03T08:00:00Z");
        jdbc.update("INSERT INTO intelligence_feishu_binding(user_id, open_id, bound_at)"
                        + " VALUES(?, 'ou-at', ?)", userId, boundAt.atOffset(ZoneOffset.UTC));

        assertThat(repository.findBoundAtByUserId(userId)).contains(boundAt);
    }

    @Test
    @DisplayName("给定未绑定用户，when按 userId 查绑定时刻，then返回 empty")
    void givenUnboundUser_whenFindBoundAtByUserId_thenEmpty() {
        Long userId = insertUser("bind_it_at_none");

        assertThat(repository.findBoundAtByUserId(userId)).isEmpty();
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 照 SubscriptionRepositoryTest 的 insertUser 先例（app_user 为 feishu_binding 的 FK 目标）。 */
    private Long insertUser(String username) {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
                + " VALUES(?, 'x', 'USER', 'APPROVED')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
}
