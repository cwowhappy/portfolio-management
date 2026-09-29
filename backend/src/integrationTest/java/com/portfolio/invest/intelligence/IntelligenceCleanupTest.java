package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.intelligence.IntelligenceCleanupService;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.time.OffsetDateTime;
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
 * 滚动清理真库集成（Testcontainers PG16 + V3 迁移）：91 天前/89 天前 raw+extract 对、
 * 过期/未过期绑定码 → cleanupNow() → 旧对删除新对保留、extract 级联无悬空、
 * 过期绑定码删未过期留。fixture 在 @BeforeEach/@AfterEach 双向清空（照
 * BriefPushIntegrationTest 清理卫生先例，保持与同容器兄弟类顺序无关）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntelligenceCleanupTest extends PostgresTestSupport {

    private static final ZoneOffset CST = ZoneOffset.ofHours(8);

    @Autowired
    IntelligenceCleanupService service;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {
        doCleanTables();
    }

    /** 共享 PG 容器跨类可见残留：raw/extract（NewsRepositoryTest 断言精确行数）、
     *  binding_code/app_user（IntelligenceMigrationTest 断言表清单与精确行数）须清空。 */
    @AfterEach
    void cleanUpForSiblingClasses() {
        doCleanTables();
    }

    private void doCleanTables() {
        // extract 经 FK ON DELETE CASCADE 随 raw 清除；binding_code 先于 app_user 删（FK）
        jdbc.update("DELETE FROM intelligence_news_raw");
        jdbc.update("DELETE FROM intelligence_binding_code");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'cleanup_it%'");
    }

    @Test
    @DisplayName("给定91天前与89天前raw+extract对，when清理，then旧对删除新对保留且extract无悬空")
    void givenOldAndFreshNewsPairs_whenCleanup_thenOldPairDeletedAndNoDanglingExtract() {
        Instant now = Instant.now();
        insertRawWithExtract("cl-old", "九十一天前的旧闻", now.minus(java.time.Duration.ofDays(91)));
        insertRawWithExtract("cl-fresh", "八十九天前的新闻", now.minus(java.time.Duration.ofDays(89)));

        service.cleanupNow();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_news_raw WHERE external_id='cl-old'",
                Integer.class)).as("91 天前 raw 应删除").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_news_raw WHERE external_id='cl-fresh'",
                Integer.class)).as("89 天前 raw 应保留").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_news_extract", Integer.class))
                .as("extract 仅剩新对一条").isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_news_extract e"
                        + " LEFT JOIN intelligence_news_raw r ON e.news_raw_id = r.id"
                        + " WHERE r.id IS NULL", Integer.class))
                .as("extract 不应有悬空行").isZero();
    }

    @Test
    @DisplayName("给定过期与未过期绑定码，when清理，then过期删未过期留")
    void givenExpiredAndLiveBindingCodes_whenCleanup_thenOnlyExpiredDeleted() {
        Long userId = insertUser("cleanup_it_u1");
        Instant now = Instant.now();
        insertBindingCode("990011", userId, now.minus(java.time.Duration.ofDays(2)));
        insertBindingCode("990022", userId, now.plus(java.time.Duration.ofMinutes(10)));

        service.cleanupNow();

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_binding_code WHERE code='990011'",
                Integer.class)).as("过期 2 天的绑定码应删除").isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM intelligence_binding_code WHERE code='990022'",
                Integer.class)).as("未过期绑定码应保留").isEqualTo(1);
    }

    // ── fixture 助手 ─────────────────────────────────────────────

    private Long insertUser(String username) {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
                + " VALUES(?, 'x', 'USER', 'APPROVED')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    private void insertRawWithExtract(String externalId, String title, Instant publishedAt) {
        jdbc.update("INSERT INTO intelligence_news_raw(source, external_id, title, published_at, stock_tags)"
                        + " VALUES('test', ?, ?, ?, '[{\"code\":\"600519\"}]'::jsonb)",
                externalId, title, publishedAt.atOffset(CST));
        Long id = jdbc.queryForObject(
                "SELECT id FROM intelligence_news_raw WHERE external_id=?", Long.class, externalId);
        jdbc.update("INSERT INTO intelligence_news_extract"
                        + "(news_raw_id, stock_codes, industry_codes, summary, direction,"
                        + " importance, status, model, extracted_at)"
                        + " VALUES(?, '[\"600519\"]'::jsonb, '[\"801010\"]'::jsonb, '摘要', 'BULLISH',"
                        + " 70, 'SUCCESS', 'test-model', ?)",
                id, OffsetDateTime.now(CST));
    }

    private void insertBindingCode(String code, Long userId, Instant expiresAt) {
        jdbc.update("INSERT INTO intelligence_binding_code(code, user_id, expires_at) VALUES(?, ?, ?)",
                code, userId, expiresAt.atOffset(CST));
    }
}
