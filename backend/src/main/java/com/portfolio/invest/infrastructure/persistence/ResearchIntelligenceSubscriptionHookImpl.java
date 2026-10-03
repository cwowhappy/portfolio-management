package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.application.research.IntelligenceSubscriptionHook;
import com.portfolio.invest.application.research.IntelligenceSubscriptionHook.IntelligenceTarget;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 持仓情报挂接实现（D12，决策 #26）：查 research_project 的
 * status=ACTIVE ∧ current_stage=POSITION ∧ intelligence_alert_enabled=true（V2 表 + V3 加列；
 * 开关写路径 = ResearchApplicationService#setIntelligenceAlert，M16-F11）。
 * 走 {@link JdbcTemplate} 原生 SQL 而非 JPA derived query——返回投影
 * {@link IntelligenceTarget}（userId/projectId/stockCode/stockName）而非聚合（开关虽已入
 * {@code ResearchProject} 域字段与 JPA 映射，本查询仍无需装回聚合），照
 * BindingCodeRepositoryImpl 的单查询先例最薄。
 *
 * <p>照 {@link com.portfolio.invest.infrastructure.im.FeishuResearchFalsifierNotifier} 模式：
 * @Component 无条件注册；尽力而为——任何失败记 WARN 返回空集，绝不抛
 * （推送主流程照常走订阅命中一路）。
 */
@Component
public class ResearchIntelligenceSubscriptionHookImpl implements IntelligenceSubscriptionHook {

    private static final Logger log = LoggerFactory.getLogger(ResearchIntelligenceSubscriptionHookImpl.class);

    private final JdbcTemplate jdbc;

    public ResearchIntelligenceSubscriptionHookImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<IntelligenceTarget> activePositionTargets() {
        try {
            return jdbc.query("""
                    SELECT user_id, id, stock_code, stock_name FROM research_project
                    WHERE status = 'ACTIVE' AND current_stage = 'POSITION'
                      AND intelligence_alert_enabled = TRUE
                    ORDER BY user_id, id
                    """,
                    (rs, i) -> new IntelligenceTarget(rs.getLong("user_id"), rs.getLong("id"),
                            rs.getString("stock_code"), stockNameOrCode(rs)));
        } catch (Exception e) { // 尽力而为：挂接失败按空集处理，不拖垮推送主流程
            log.warn("持仓情报挂接查询失败，按空集处理：{}", e.getMessage());
            return List.of();
        }
    }

    /** stock_name 空白（V2 NOT NULL 但可为空串）回退 stock_code，展示不落空。 */
    private static String stockNameOrCode(ResultSet rs) throws SQLException {
        String name = rs.getString("stock_name");
        return name == null || name.isBlank() ? rs.getString("stock_code") : name;
    }
}
