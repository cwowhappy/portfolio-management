package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.intelligence.BindingCodeRepository;
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
 * 绑定码仓库真库契约（Testcontainers PG16 + V3 intelligence_binding_code，D8）：
 * trySave 新码落行（used_at 为 NULL——未核销）、PK 冲突返回 false 且不覆盖原行
 * （ON CONFLICT DO NOTHING——由调用方换码重生成，不抛约束违例）。fixture 需
 * app_user 行（FK），照 FeishuBindingRepositoryTest 的 insertUser 先例。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BindingCodeRepositoryTest extends PostgresTestSupport {

    @Autowired
    BindingCodeRepository repository;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM intelligence_binding_code WHERE user_id IN"
                + " (SELECT id FROM app_user WHERE username LIKE 'code_it%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'code_it%'");
    }

    @Test
    @DisplayName("给定新码，when trySave，then返回 true 且落行（used_at 为 NULL 未核销）")
    void givenFreshCode_whenTrySave_thenTrueAndRowInserted() {
        Long userId = insertUser("code_it_ok");
        Instant expiresAt = Instant.parse("2026-10-03T09:10:00Z");

        boolean saved = repository.trySave("123456", userId, expiresAt);

        assertThat(saved).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM intelligence_binding_code WHERE code = '123456'", Long.class))
                .isEqualTo(userId);
        assertThat(readExpiresAt("123456")).isEqualTo(expiresAt);
        assertThat(jdbc.queryForObject(
                "SELECT used_at FROM intelligence_binding_code WHERE code = '123456'",
                java.sql.Timestamp.class)).as("未核销").isNull();
    }

    @Test
    @DisplayName("给定已存码，when trySave 同码，then返回 false 且原行不被覆盖（冲突由调用方换码重试）")
    void givenExistingCode_whenTrySaveSameCode_thenFalseAndOriginalUntouched() {
        Long userId = insertUser("code_it_conflict");
        Long otherUser = insertUser("code_it_other");
        repository.trySave("654321", userId, Instant.parse("2026-10-03T09:00:00Z"));

        boolean saved = repository.trySave("654321", otherUser, Instant.parse("2026-10-03T09:30:00Z"));

        assertThat(saved).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT user_id FROM intelligence_binding_code WHERE code = '654321'", Long.class))
                .as("ON CONFLICT DO NOTHING——原行归属不变").isEqualTo(userId);
        assertThat(readExpiresAt("654321"))
                .as("原行失效时刻不被覆盖")
                .isEqualTo(Instant.parse("2026-10-03T09:00:00Z"));
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 读指定码的失效时刻（Timestamp → Instant 归一比较）。 */
    private Instant readExpiresAt(String code) {
        java.sql.Timestamp ts = jdbc.queryForObject(
                "SELECT expires_at FROM intelligence_binding_code WHERE code = ?",
                java.sql.Timestamp.class, code);
        return ts == null ? null : ts.toInstant();
    }

    /** 照 FeishuBindingRepositoryTest 的 insertUser 先例（app_user 为 binding_code 的 FK 目标）。 */
    private Long insertUser(String username) {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
                + " VALUES(?, 'x', 'USER', 'APPROVED')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
}
