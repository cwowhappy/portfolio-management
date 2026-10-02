package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.FeishuBindingRepository;
import com.portfolio.invest.support.PostgresTestSupport;
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

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 照 SubscriptionRepositoryTest 的 insertUser 先例（app_user 为 feishu_binding 的 FK 目标）。 */
    private Long insertUser(String username) {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
                + " VALUES(?, 'x', 'USER', 'APPROVED')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
}
