package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.application.intelligence.SubscriptionService;
import com.portfolio.invest.domain.intelligence.IntelligenceErrorCode;
import com.portfolio.invest.domain.intelligence.IntelligenceException;
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
 * 绑定闭环真库集成（Testcontainers PG16 + V3 两表，P4 Task 5）：generateCode →
 * bindByCode → 绑定表落库（ON CONFLICT 覆盖）→ findOpenId 可见；码一次性（核销后
 * 不可重用）、过期码拒绝、open_id 占用拒绝（UNIQUE 兜底）、同用户换码重绑覆盖旧
 * open_id。真库替身 SQL 受影响行数与唯一约束语义（并发护栏），service 走生产装配。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BindingFlowIntegrationTest extends PostgresTestSupport {

    @Autowired
    SubscriptionService service;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM intelligence_feishu_binding WHERE user_id IN"
                + " (SELECT id FROM app_user WHERE username LIKE 'bind_flow%')");
        jdbc.update("DELETE FROM intelligence_binding_code WHERE user_id IN"
                + " (SELECT id FROM app_user WHERE username LIKE 'bind_flow%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'bind_flow%'");
    }

    @Test
    @DisplayName("given 用户生成码，when bindByCode 核销，then绑定表落库且 findOpenId 可见")
    void givenGeneratedCode_whenBindByCode_thenBindingRowVisible() {
        Long userId = insertUser("bind_flow_ok");

        var code = service.generateCode(userId);
        service.bindByCode(code.code(), "ou-flow-a");

        assertThat(service.findOpenId(userId)).contains("ou-flow-a");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_feishu_binding WHERE user_id = ? AND open_id = 'ou-flow-a'",
                Integer.class, userId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT used_at IS NOT NULL FROM intelligence_binding_code WHERE code = ?",
                Boolean.class, code.code())).as("码行已核销").isTrue();
    }

    @Test
    @DisplayName("given 已核销的码，when 再次 bindByCode，then抛 BINDING_CODE_EXPIRED（一次性）")
    void givenRedeemedCode_whenBindAgain_thenExpiredRejection() {
        Long userId = insertUser("bind_flow_once");
        Long other = insertUser("bind_flow_once_other");
        var code = service.generateCode(userId);
        service.bindByCode(code.code(), "ou-once");

        assertThatThrownBy(() -> service.bindByCode(code.code(), "ou-once-other"))
                .isInstanceOfSatisfying(IntelligenceException.class, e ->
                        assertThat(e.code()).isEqualTo(IntelligenceErrorCode.BINDING_CODE_EXPIRED));
        assertThat(service.findOpenId(other)).as("他人未落绑定").isEmpty();
        assertThat(service.findOpenId(userId)).as("原绑定不被覆盖").contains("ou-once");
    }

    @Test
    @DisplayName("given 过期码，when bindByCode，then抛 BINDING_CODE_EXPIRED 且不落绑定")
    void givenExpiredCode_whenBindByCode_thenRejected() {
        Long userId = insertUser("bind_flow_exp");
        jdbc.update("INSERT INTO intelligence_binding_code(code, user_id, expires_at)"
                        + " VALUES('900001', ?, ?)",
                userId, Instant.now().minusSeconds(60).atOffset(ZoneOffset.UTC));

        assertThatThrownBy(() -> service.bindByCode("900001", "ou-exp"))
                .isInstanceOfSatisfying(IntelligenceException.class, e ->
                        assertThat(e.code()).isEqualTo(IntelligenceErrorCode.BINDING_CODE_EXPIRED));
        assertThat(service.findOpenId(userId)).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT used_at FROM intelligence_binding_code WHERE code = '900001'",
                java.sql.Timestamp.class)).as("过期码不被打核销标").isNull();
    }

    @Test
    @DisplayName("given open_id 已绑其他用户，when bindByCode，then抛 OPEN_ID_TAKEN")
    void givenOpenIdTaken_whenBindByCode_thenTakenRejection() {
        Long owner = insertUser("bind_flow_owner");
        Long taker = insertUser("bind_flow_taker");
        service.bindByCode(service.generateCode(owner).code(), "ou-shared");

        assertThatThrownBy(() ->
                service.bindByCode(service.generateCode(taker).code(), "ou-shared"))
                .isInstanceOfSatisfying(IntelligenceException.class, e ->
                        assertThat(e.code()).isEqualTo(IntelligenceErrorCode.OPEN_ID_TAKEN));
        assertThat(service.findOpenId(taker)).isEmpty();
        assertThat(service.findOpenId(owner)).as("原绑定不受影响").contains("ou-shared");
    }

    @Test
    @DisplayName("given 用户换码重绑新 open_id，when bindByCode，then覆盖旧绑定（单行新值）")
    void givenRebindWithNewOpenId_whenBindByCode_thenOverwritten() {
        Long userId = insertUser("bind_flow_rebind");
        service.bindByCode(service.generateCode(userId).code(), "ou-old");

        service.bindByCode(service.generateCode(userId).code(), "ou-new");

        assertThat(service.findOpenId(userId)).contains("ou-new");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_feishu_binding WHERE user_id = ?",
                Integer.class, userId)).as("重绑覆盖不增行").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_feishu_binding WHERE open_id = 'ou-old'",
                Integer.class)).as("旧 open_id 随行释放").isZero();
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 照 BindingCodeRepositoryTest 的 insertUser 先例（app_user 为两绑定表 FK 目标）。 */
    private Long insertUser(String username) {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
                + " VALUES(?, 'x', 'USER', 'APPROVED')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
}
