package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.application.intelligence.CollectorRunInspectPort;
import com.portfolio.invest.application.intelligence.CollectorRunInspectPort.TaskRunSummary;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * collector 运行记录巡读实现（trading_calendar 同款先例）：JdbcTemplate 只读
 * collector Alembic 建的跨服务运维表 collector_task_run（JOIN collector_task 按
 * task_code 过滤）。只回终态行（finished_at IS NOT NULL——running 前置行跳过），
 * 窗口按 started_at ≥ now() - recentDays 天（DB now()，与采集写入时钟同源）。
 *
 * <p>表不存在（backend 独立部署 / collector 首刷前）与访问异常降级空列表 +
 * log.warn——映射「无 run 的月不参与判定」，冷启动不阻断巡检。
 */
@Repository
public class CollectorRunInspectPortImpl implements CollectorRunInspectPort {

    private static final Logger log = LoggerFactory.getLogger(CollectorRunInspectPortImpl.class);

    private final JdbcTemplate jdbc;

    public CollectorRunInspectPortImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<TaskRunSummary> runsOf(String taskCode, int recentDays) {
        try {
            return jdbc.query("""
                            SELECT r.status, r.source_used, r.started_at
                            FROM collector_task_run r
                            JOIN collector_task t ON t.id = r.task_id
                            WHERE t.task_code = ?
                              AND r.finished_at IS NOT NULL
                              AND r.started_at >= now() - make_interval(days => ?)
                            ORDER BY r.started_at
                            """,
                    (rs, i) -> new TaskRunSummary(rs.getString("status"), rs.getString("source_used"),
                            instant(rs, "started_at")),
                    taskCode, recentDays);
        } catch (DataAccessException e) {
            log.warn("collector_task_run 不可读（未建表/访问异常），按无 run 处理: taskCode={}", taskCode, e);
            return List.of();
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
