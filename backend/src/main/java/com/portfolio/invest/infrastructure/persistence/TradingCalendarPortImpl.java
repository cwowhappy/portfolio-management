package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.application.intelligence.TradingCalendarPort;
import java.sql.ResultSet;
import java.time.DayOfWeek;
import java.time.LocalDate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 交易日历端口实现（D5）：直读 collector Alembic 建的跨服务契约表 trading_calendar
 * （行存在即交易日，PRIMARY KEY trade_date）。
 *
 * <p>裁决语义：当日有行 → 交易日；当日无行但日历含当日及以后的行（日历覆盖当下）→
 * 权威非交易日（节假日）；日历不含任何 ≥ 当日的行（表空/未刷新/未建表——冷启动）→
 * 降级「周一~周五」近似 + log.warn，不阻断简报链路。表不存在（backend 测试库/采集侧
 * 首刷前）同降级。
 */
@Repository
public class TradingCalendarPortImpl implements TradingCalendarPort {

    private static final Logger log = LoggerFactory.getLogger(TradingCalendarPortImpl.class);

    private final JdbcTemplate jdbc;

    public TradingCalendarPortImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean isTradingDay(LocalDate date) {
        try {
            if (exists("SELECT 1 FROM trading_calendar WHERE trade_date = ?", date)) {
                return true;
            }
            // 当日无行：日历能否裁决当下？（覆盖则当日缺席即权威节假日）
            if (exists("SELECT 1 FROM trading_calendar WHERE trade_date >= ?", date)) {
                return false;
            }
        } catch (DataAccessException e) {
            log.warn("交易日历表不可读（未建表/访问异常），降级周一~周五近似: {}", date, e);
            return weekdayApprox(date);
        }
        log.warn("交易日历无 {} 及以后的记录（表空或未刷新），降级周一~周五近似", date);
        return weekdayApprox(date);
    }

    /** 单列存在性查询（ResultSet.next 即命中，空结果 false）。 */
    private boolean exists(String sql, LocalDate date) {
        return jdbc.query(sql, ResultSet::next, date);
    }

    private static boolean weekdayApprox(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
    }
}
