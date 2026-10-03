package com.portfolio.invest.intelligence;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook.IntelligenceTarget;
import com.portfolio.invest.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 持仓情报挂接真库契约（Testcontainers PG16 + V2/V3 迁移，D12/决策 #26）：
 * activePositionTargets 只取 status=ACTIVE ∧ current_stage=POSITION ∧
 * intelligence_alert_enabled=true 的研究项目；stock_name 空白回退 stock_code；
 * 查询失败不抛（按空集处理——尽力而为，推送主流程不被挂接失败打断）。
 * fixture 需 app_user 行（FK），照 SubscriptionRepositoryTest 的 insertUser 先例；
 * @BeforeEach/@AfterEach 双向清空（兄弟类残留互不污染）。
 */
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResearchIntelligenceSubscriptionHookImplTest extends PostgresTestSupport {

    @Autowired
    IntelligenceSubscriptionHook hook;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbc.update("DELETE FROM research_project WHERE user_id IN"
                + " (SELECT id FROM app_user WHERE username LIKE 'hook_it%')");
        jdbc.update("DELETE FROM app_user WHERE username LIKE 'hook_it%'");
    }

    @Test
    @DisplayName("给定 POSITION+开/POSITION+关/STRATEGY+开/ARCHIVED+POSITION+开 四项目，when查持仓挂接，then只第一个命中")
    void givenProjectsInVariousStagesAndSwitches_whenActivePositionTargets_thenOnlyEnabledPositionActiveHit() {
        Long userId = insertUser("hook_it_filter");
        Long hit = insertProject(userId, "600519", "贵州茅台", "POSITION", "ACTIVE", true);
        insertProject(userId, "300750", "宁德时代", "POSITION", "ACTIVE", false);
        insertProject(userId, "000001", "平安银行", "STRATEGY", "ACTIVE", true);
        insertProject(userId, "601318", "中国平安", "POSITION", "ARCHIVED", true);

        List<IntelligenceTarget> targets = hook.activePositionTargets();

        assertThat(targets).hasSize(1);
        IntelligenceTarget target = targets.getFirst();
        assertThat(target.userId()).isEqualTo(userId);
        assertThat(target.projectId()).isEqualTo(hit);
        assertThat(target.stockCode()).isEqualTo("600519");
        assertThat(target.stockName()).isEqualTo("贵州茅台");
    }

    @Test
    @DisplayName("给定命中项目 stock_name 空白，when查持仓挂接，then回退 stock_code（卡片展示不落空）")
    void givenHitProjectWithBlankStockName_whenActivePositionTargets_thenFallsBackToStockCode() {
        Long userId = insertUser("hook_it_blank");
        insertProject(userId, "688981", "  ", "POSITION", "ACTIVE", true);

        List<IntelligenceTarget> targets = hook.activePositionTargets();

        assertThat(targets).hasSize(1);
        assertThat(targets.getFirst().stockName()).isEqualTo("688981");
    }

    @Test
    @DisplayName("给定库中无符合条件的项目，when查持仓挂接，then返回空集（推送侧两路 union 自然短路）")
    void givenNoQualifiedProjects_whenActivePositionTargets_thenEmptyList() {
        Long userId = insertUser("hook_it_none");
        insertProject(userId, "600519", "贵州茅台", "REVIEW", "ACTIVE", true);

        assertThat(hook.activePositionTargets()).isEmpty();
    }

    // ── fixture 助手 ───────────────────────────────────────────────

    /** 照 SubscriptionRepositoryTest 的 insertUser 先例（app_user 为 research_project 的 FK 目标）。 */
    private Long insertUser(String username) {
        jdbc.update("INSERT INTO app_user(username, password_hash, role, status)"
                + " VALUES(?, 'x', 'USER', 'APPROVED')", username);
        return jdbc.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, username);
    }

    private Long insertProject(Long userId, String stockCode, String stockName, String stage,
                               String status, boolean alertEnabled) {
        jdbc.update("INSERT INTO research_project"
                + " (user_id, stock_code, stock_name, title, current_stage, status, intelligence_alert_enabled)"
                + " VALUES(?, ?, ?, ?, ?, ?, ?)",
                userId, stockCode, stockName, "测试项目-" + stockCode, stage, status, alertEnabled);
        return jdbc.queryForObject("SELECT id FROM research_project WHERE user_id=? AND stock_code=?",
                Long.class, userId, stockCode);
    }
}
