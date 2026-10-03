package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.application.intelligence.BriefPushService;
import com.portfolio.invest.application.intelligence.IntelligencePushPort;
import com.portfolio.invest.config.InvestProperties;
import com.portfolio.invest.domain.intelligence.BriefRepository;
import com.portfolio.invest.domain.intelligence.DailyBrief;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 盘前简报推送真库集成（Testcontainers PG16 + @MockitoBean 桩推送端口，绝不真调飞书）：
 * seed 当日档 + trading_calendar → pushBrief → intelligence_push_log 留痕行断言
 * （push_type CHECK、target=chatId、ref_table/ref_id 指向简报行、OK/FAIL、user_id 可空）。
 * im 凭证经 @SpringBootTest properties 注入（dialogue-enabled 缺省 false，ws 客户端早退
 * 无网络副作用；上下文缓存键独立，不污染其余集成上下文）。
 */
@SpringBootTest(properties = {
        "invest.im.app-id=cli_it",
        "invest.im.app-secret=sec_it",
        "invest.im.chat-id=oc_it"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BriefPushIntegrationTest extends PostgresTestSupport {

    private static final ZoneId CST = ZoneId.of("Asia/Shanghai");

    @Autowired
    BriefPushService service;
    @Autowired
    BriefRepository briefRepository;
    @Autowired
    InvestProperties props;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    IntelligencePushPort pushPort;

    @BeforeEach
    void cleanTables() {
        doCleanTables();
        when(pushPort.sendToGroup(any(), any(), anyList())).thenReturn(true);
    }

    /**
     * 共享 PG 容器跨类可见残留：IntelligenceMigrationTest 对 push_log 断言精确行数，
     * 本类写过的留痕/简报表须在 @AfterEach 清空，保持类间顺序无关。
     */
    @AfterEach
    void cleanUpForSiblingClasses() {
        doCleanTables();
    }

    private void doCleanTables() {
        // trading_calendar：collector Alembic 契约表，backend 测试库自建（同 BriefGenerationIntegrationTest）
        jdbc.execute("CREATE TABLE IF NOT EXISTS trading_calendar (trade_date DATE PRIMARY KEY)");
        jdbc.update("DELETE FROM intelligence_push_log");
        jdbc.update("DELETE FROM intelligence_daily_brief");
        jdbc.update("DELETE FROM trading_calendar");
        jdbc.update("INSERT INTO trading_calendar VALUES (?)",
                java.sql.Date.valueOf(LocalDate.now(CST)));
        // P4 个性化分流后群版仅当「存在 pushEnabled 且未绑定用户」才发：seed 一名开启推送、
        // 未绑定飞书的用户保住群兜底路径（本类断言群推留痕行的前提）
        jdbc.update("DELETE FROM intelligence_subscription WHERE user_id IN"
                + " (SELECT id FROM app_user WHERE username = 'brief-push-user')");
        jdbc.update("DELETE FROM app_user WHERE username = 'brief-push-user'");
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
                + " VALUES('brief-push-user', 'x', 'USER', 'APPROVED')");
        jdbc.update("INSERT INTO intelligence_subscription(user_id, push_enabled)"
                + " SELECT id, TRUE FROM app_user WHERE username = 'brief-push-user'");
    }

    @Test
    @DisplayName("给定当日GENERATED档，when推送，then群推卡片且push_log留痕OK全字段真库落行")
    void givenGeneratedBrief_whenPush_thenOkLogged() {
        LocalDate today = LocalDate.now(CST);
        briefRepository.save(DailyBrief.generated(today, "# 盘前情报速递", List.of("600519"),
                "test-model", Instant.now()));

        service.pushBrief();

        verify(pushPort).sendToGroup(eq("【盘前简报】" + today), eq("blue"), anyList());
        Long briefId = jdbc.queryForObject(
                "SELECT id FROM intelligence_daily_brief WHERE trade_date = ?", Long.class, today);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT user_id, push_type, target, ref_table, ref_id, status, error, sent_at"
                        + " FROM intelligence_push_log");
        assertThat(row.get("user_id")).isNull();          // 群推无归属（可空 FK 落 null）
        assertThat(row.get("push_type")).isEqualTo("BRIEF");
        assertThat(row.get("target")).isEqualTo(props.getIm().getChatId());
        assertThat(row.get("ref_table")).isEqualTo("intelligence_daily_brief");
        assertThat(((Number) row.get("ref_id")).longValue()).isEqualTo(briefId);
        assertThat(row.get("status")).isEqualTo("OK");
        assertThat(row.get("error")).isNull();
        assertThat(row.get("sent_at")).isNotNull();
    }

    @Test
    @DisplayName("给定推送端口返回失败，when推送，thenpush_log留痕FAIL带error")
    void givenPushFails_whenPush_thenFailLoggedWithError() {
        when(pushPort.sendToGroup(any(), any(), anyList())).thenReturn(false);
        briefRepository.save(DailyBrief.generated(LocalDate.now(CST), "# 内容", List.of(),
                "test-model", Instant.now()));

        service.pushBrief();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, error FROM intelligence_push_log");
        assertThat(row.get("status")).isEqualTo("FAIL");
        assertThat((String) row.get("error")).isNotBlank();
    }

    @Test
    @DisplayName("给定当日FAILED档，when推送，then红色失败提示卡仍留痕OK")
    void givenFailedBrief_whenPush_thenFailureNoticeCardLogged() {
        briefRepository.save(DailyBrief.failed(LocalDate.now(CST), "llm down", "test-model",
                Instant.now()));

        service.pushBrief();

        verify(pushPort).sendToGroup(any(), eq("red"), anyList());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM intelligence_push_log", String.class)).isEqualTo("OK");
    }
}
