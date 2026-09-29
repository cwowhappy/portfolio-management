package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.invest.support.PostgresTestSupport;
import java.sql.Date;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V3__intelligence.sql 迁移契约：@SpringBootTest 在真实 PG（Testcontainers）上跑全量
 * 迁移（V1+V2+V3）后断言——15 张 intelligence 表存在、业务键唯一约束与级联删除生效、
 * pg_trgm 扩展与 §2.2 索引齐全、research_project 加列默认 TRUE、宏观日历种子
 * （2026Q4~2027 五指标）落库。DDL 逐表对照设计规格 §2.1/§2.2。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IntelligenceMigrationTest extends PostgresTestSupport {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("V3 建 15 张 intelligence 表且业务键唯一约束生效")
    void whenV3Applied_thenCreatesFifteenIntelligenceTables() {
        for (String t : List.of("intelligence_news_raw", "intelligence_news_extract",
                "intelligence_announcement", "intelligence_announcement_extract",
                "intelligence_policy_raw", "intelligence_policy_event",
                "intelligence_macro_series", "intelligence_macro_calendar",
                "intelligence_source_switch", "intelligence_daily_brief",
                "intelligence_subscription", "intelligence_subscription_stock",
                "intelligence_feishu_binding", "intelligence_binding_code",
                "intelligence_push_log")) {
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_name=?", Integer.class, t))
                .as("表 %s 应由 V3 创建", t)
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("业务键唯一约束与级联删除")
    void whenDuplicateBusinessKeyInserted_thenRejectedAndExtractCascades() {
        jdbc.update("INSERT INTO intelligence_news_raw(source, external_id, title, published_at)"
            + " VALUES('eastmoney','n1','t',now())");
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO intelligence_news_raw(source, external_id, title, published_at)"
                + " VALUES('eastmoney','n1','t2',now())"))
            .isInstanceOf(DataIntegrityViolationException.class);
        Long id = jdbc.queryForObject(
            "SELECT id FROM intelligence_news_raw WHERE external_id='n1'", Long.class);
        jdbc.update("INSERT INTO intelligence_news_extract(news_raw_id, status) VALUES(?, 'PENDING')", id);
        jdbc.update("DELETE FROM intelligence_news_raw WHERE id=?", id);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_news_extract", Integer.class)).isZero();
    }

    @Test
    @DisplayName("feishu_binding 的 open_id 唯一（重复绑定他人被拒）")
    void whenOpenIdBoundToAnotherUser_thenRejected() {
        Long u1 = insertUser("intelligence_mig_u1");
        Long u2 = insertUser("intelligence_mig_u2");
        jdbc.update("INSERT INTO intelligence_feishu_binding(user_id, open_id) VALUES(?, 'ou_same')", u1);
        assertThatThrownBy(() -> jdbc.update(
            "INSERT INTO intelligence_feishu_binding(user_id, open_id) VALUES(?, 'ou_same')", u2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("pg_trgm 扩展与 trgm 索引可用，§2.2 索引齐全")
    void whenTrgmQueryExecuted_thenExtensionAndIndexesReady() {
        Integer hit = jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_news_raw WHERE title % '政策利率'", Integer.class);
        assertThat(hit).isZero(); // 扩展不存在则此查询抛异常
        for (String idx : List.of(
                "idx_intelligence_news_raw_title_trgm",
                "idx_intelligence_announcement_title_trgm",
                "idx_intelligence_daily_brief_content_trgm",
                "idx_intelligence_news_raw_published",
                "idx_intelligence_announcement_published",
                "idx_intelligence_policy_raw_published",
                "idx_intelligence_announcement_stock",
                "idx_intelligence_macro_series_indicator")) {
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE indexname=?", Integer.class, idx))
                .as("索引 %s 应由 V3 创建", idx)
                .isEqualTo(1);
        }
    }

    @Test
    @DisplayName("research_project 加列默认 TRUE；日历种子 2026Q4~2027 五指标齐全")
    void whenColumnAltered_thenDefaultsTrueAndCalendarSeedPresent() {
        // 预检裁定：空表上「SELECT intelligence_alert_enabled ... LIMIT 1」抛 EmptyResultDataAccessException，
        // 改为 ① information_schema 断言列存在；② 插入 fixture 行断言默认 TRUE（V2 列以 V2__research.sql 为准）
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema='public'"
                + " AND table_name='research_project' AND column_name='intelligence_alert_enabled'",
            Integer.class)).isEqualTo(1);
        Long userId = insertUser("intelligence_mig_u3");
        jdbc.update("INSERT INTO research_project(user_id, stock_code, stock_name, title, current_stage)"
            + " VALUES(?, '600519', '贵州茅台', '迁移验证项目', 'POSITION')", userId);
        assertThat(jdbc.queryForObject(
            "SELECT intelligence_alert_enabled FROM research_project WHERE user_id=?",
            Boolean.class, userId)).isTrue();

        // 日历种子：五指标 × 2026-10~2027-12（15 个月）= 75 行
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_macro_calendar", Integer.class)).isEqualTo(75);
        assertThat(jdbc.queryForObject(
            "SELECT count(DISTINCT indicator) FROM intelligence_macro_calendar",
            Integer.class)).isEqualTo(5);
        // CPI/PPI 每月 9 日；PMI 月末（2 月按月末 28/29 日）；LPR 每月 20 日；AFMI 每月 12 日
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_macro_calendar WHERE indicator=? AND expected_date=?",
            Integer.class, "CPI", Date.valueOf("2026-10-09"))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_macro_calendar WHERE indicator=? AND expected_date=?",
            Integer.class, "PMI", Date.valueOf("2027-02-28"))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_macro_calendar WHERE indicator=? AND expected_date=?",
            Integer.class, "LPR", Date.valueOf("2027-12-20"))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_macro_calendar WHERE indicator=? AND expected_date=?",
            Integer.class, "AFMI", Date.valueOf("2026-12-12"))).isEqualTo(1);
        // 不存在非法日期行（如 2027-02-29 / 2027-04-31——非法日期本就无法落库，此处防生成式手误）
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM intelligence_macro_calendar WHERE expected_date < DATE '2026-10-01'"
                + " OR expected_date > DATE '2027-12-31'", Integer.class)).isZero();
    }

    private Long insertUser(String username) {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
            + " VALUES(?, 'x', 'USER', 'APPROVED')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }
}
