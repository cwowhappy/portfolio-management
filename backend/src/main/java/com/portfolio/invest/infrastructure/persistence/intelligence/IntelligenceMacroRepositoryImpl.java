package com.portfolio.invest.infrastructure.persistence.intelligence;

import com.portfolio.invest.domain.intelligence.MacroCalendarEntry;
import com.portfolio.invest.domain.intelligence.MacroPoint;
import com.portfolio.invest.domain.intelligence.MacroRepository;
import com.portfolio.invest.domain.intelligence.SourceSwitch;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 宏观域仓库实现（intelligence_macro_series / intelligence_macro_calendar /
 * intelligence_source_switch + treasury_yield_curve 跨表只读）。
 *
 * <p>全走 JdbcTemplate 原生 SQL，照 {@link IntelligenceNewsRepositoryImpl} 先例：
 * DISTINCT ON 是 PG 方言（JPQL 无法表达）；macro_series 与 treasury_yield_curve 为
 * collector 写入的跨服务契约表，本仓库只读；source_switch 由本仓库独占写入
 * （单语句 INSERT，无需显式事务）。表结构的 JPA 注册载体见同包三个 *JpaEntity。
 */
@Repository
public class IntelligenceMacroRepositoryImpl implements MacroRepository {

    private static final String SERIES_COLS = """
            SELECT indicator, period, period_type, value, yoy, source_url, source_note
            """;

    /** 同指标 period 格式恒定（月 YYYY-MM / 日 YYYY-MM-DD），字典序即时间序。 */
    private static final RowMapper<MacroPoint> POINT_MAPPER = (rs, i) -> new MacroPoint(
            rs.getString("indicator"), rs.getString("period"), rs.getString("period_type"),
            rs.getBigDecimal("value"), rs.getBigDecimal("yoy"),
            rs.getString("source_url"), rs.getString("source_note"));

    private static final RowMapper<MacroCalendarEntry> CALENDAR_MAPPER = (rs, i) -> new MacroCalendarEntry(
            rs.getString("indicator"), rs.getObject("expected_date", LocalDate.class),
            rs.getString("frequency"), rs.getString("source_site"), instant(rs, "updated_at"));

    private final JdbcTemplate jdbc;

    public IntelligenceMacroRepositoryImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<MacroPoint> findLatestPerIndicator() {
        // DISTINCT ON (indicator) 取每指标 period 最大（最新）一期，走 idx_(indicator, period DESC)
        List<MacroPoint> latest = new ArrayList<>(jdbc.query("""
                        SELECT DISTINCT ON (indicator) indicator, period, period_type, value, yoy, source_url, source_note
                        FROM intelligence_macro_series
                        ORDER BY indicator, period DESC
                        """, POINT_MAPPER));
        latest.addAll(treasuryLatestPoints());
        return latest;
    }

    /**
     * 国债收益率合成（跨表只读）：1Y/10Y 各取自身最新交易日（DISTINCT ON (term)——
     * 部分采集日单期限缺行时另一期限仍给最新点）；外层 CASE 固定 TY1Y 在 TY10Y 前
     * （指标码字典序 TY10Y &lt; TY1Y，展示口径以 1Y→10Y 为自然序）；yoy/源字段缺席
     * 为 null 不编造。
     */
    private List<MacroPoint> treasuryLatestPoints() {
        return jdbc.query("""
                        SELECT term, trading_day, yield FROM (
                            SELECT DISTINCT ON (term) term, trading_day, yield
                            FROM treasury_yield_curve
                            WHERE term IN ('1Y', '10Y')
                            ORDER BY term, trading_day DESC
                        ) t
                        ORDER BY CASE t.term WHEN '1Y' THEN 1 ELSE 2 END
                        """,
                (rs, i) -> new MacroPoint(
                        "1Y".equals(rs.getString("term"))
                                ? MacroPoint.INDICATOR_TY1Y : MacroPoint.INDICATOR_TY10Y,
                        rs.getObject("trading_day", LocalDate.class).toString(),
                        "DAY", rs.getBigDecimal("yield"), null, null, null));
    }

    @Override
    public List<MacroPoint> findSeries(String indicator, int limit) {
        return jdbc.query(SERIES_COLS + """
                FROM intelligence_macro_series
                WHERE indicator = ?
                ORDER BY period DESC
                LIMIT ?
                """, POINT_MAPPER, indicator, limit);
    }

    @Override
    public List<MacroCalendarEntry> findCalendarBetween(LocalDate from, LocalDate to) {
        return jdbc.query("""
                SELECT indicator, expected_date, frequency, source_site, updated_at
                FROM intelligence_macro_calendar
                WHERE expected_date >= ? AND expected_date <= ?
                ORDER BY expected_date, indicator
                """, CALENDAR_MAPPER, from, to);
    }

    @Override
    public void insertSourceSwitch(String indicator, String fromSource, String toSource, String reason) {
        // switched_at 落库默认 now()；幂等由调用方「状态变化才插」保证（见接口 javadoc）
        jdbc.update("""
                INSERT INTO intelligence_source_switch (indicator, from_source, to_source, reason)
                VALUES (?, ?, ?, ?)
                """, indicator, fromSource, toSource, reason);
    }

    @Override
    public Optional<SourceSwitch> findLatestSwitch(String indicator) {
        List<SourceSwitch> latest = jdbc.query("""
                SELECT from_source, to_source, switched_at
                FROM intelligence_source_switch
                WHERE indicator = ?
                ORDER BY switched_at DESC
                LIMIT 1
                """, (rs, i) -> new SourceSwitch(rs.getString("from_source"),
                rs.getString("to_source"), instant(rs, "switched_at")), indicator);
        return latest.stream().findFirst();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
