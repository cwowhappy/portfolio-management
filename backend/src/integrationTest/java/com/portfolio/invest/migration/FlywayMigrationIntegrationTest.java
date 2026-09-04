package com.portfolio.invest.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.support.PostgresTestSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway 迁移契约：@SpringBootTest 在真实 PG（Testcontainers）上跑全量迁移后断言。
 *
 * <p>估值相关表（V3/V4）是 collector（Python 采集服务）写入、后端读取的跨服务契约
 * （见 V3/V4 文件头注释与 collector 任务的 target_table），此处断言其关键列名/类型/
 * 可空性与唯一约束，防迁移脚本静默破坏契约。断言聚焦契约关键点，不做全表逐列镜像。
 */
@SpringBootTest
class FlywayMigrationIntegrationTest extends PostgresTestSupport {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DisplayName("全部迁移脚本应用成功且无失败记录")
    @Test
    void allMigrationsAppliedWithNoFailures() {
        List<String> versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE type = 'SQL' ORDER BY installed_rank",
                String.class);
        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");

        Integer failed = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = false", Integer.class);
        assertThat(failed).isZero();
    }

    @DisplayName("估值快照表契约")
    @Test
    void valuationSnapshotTableContract() {
        assertPrimaryKey("valuation_snapshot", "id");
        assertColumn("valuation_snapshot", "trading_day", "date", false);
        assertColumn("valuation_snapshot", "pe_median", "numeric", false, 12, 4);
        assertColumn("valuation_snapshot", "pb_median", "numeric", false, 12, 4);
        assertColumn("valuation_snapshot", "net_breaker_count", "integer", false);
        assertColumn("valuation_snapshot", "net_breaker_ratio", "numeric", false, 8, 4);
        assertUniqueColumns("valuation_snapshot", "trading_day");
    }

    @DisplayName("行业与指数估值表契约")
    @Test
    void industryAndIndexValuationTableContract() {
        assertColumn("industry_valuation", "trading_day", "date", false);
        assertColumn("industry_valuation", "industry_code", "character varying", false);
        assertColumn("industry_valuation", "pe", "numeric", true);
        assertUniqueColumns("industry_valuation", "trading_day,industry_code");

        assertColumn("index_valuation_history", "trading_day", "date", false);
        assertColumn("index_valuation_history", "index_code", "character varying", false);
        assertUniqueColumns("index_valuation_history", "trading_day,index_code");
    }

    @DisplayName("国债收益率曲线与指数成分表契约")
    @Test
    void treasuryYieldCurveAndIndexConstituentTableContract() {
        // V4：treasury_yield 迁移到 treasury_yield_curve 后废弃旧表
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('public.treasury_yield')", String.class))
                .isNull();

        assertColumn("treasury_yield_curve", "trading_day", "date", false);
        assertColumn("treasury_yield_curve", "term", "character varying", false);
        assertColumn("treasury_yield_curve", "yield", "numeric", false, 8, 4);
        assertUniqueColumns("treasury_yield_curve", "trading_day,term");

        assertColumn("index_constituent", "index_code", "character varying", false);
        assertColumn("index_constituent", "stock_code", "character varying", false);
        assertColumn("index_constituent", "weight", "numeric", true);
        assertUniqueColumns("index_constituent", "index_code,stock_code");
    }

    @DisplayName("申万行业映射表契约")
    @Test
    void shenwanIndustryMappingTableContract() {
        assertPrimaryKey("shenwan_industry_mapping", "id");
        assertColumn("shenwan_industry_mapping", "stock_code", "character varying", false);
        assertColumn("shenwan_industry_mapping", "industry_code", "character varying", false);
        assertUniqueColumns("shenwan_industry_mapping", "stock_code");
    }

    private void assertColumn(String table, String column, String dataType, boolean nullable) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT data_type, is_nullable FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                table, column);
        assertThat(row.get("data_type")).as("%s.%s 类型", table, column).isEqualTo(dataType);
        assertThat(row.get("is_nullable")).as("%s.%s 可空性", table, column).isEqualTo(nullable ? "YES" : "NO");
    }

    private void assertColumn(String table, String column, String dataType, boolean nullable,
                              int precision, int scale) {
        assertColumn(table, column, dataType, nullable);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT numeric_precision, numeric_scale FROM information_schema.columns"
                        + " WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
                table, column);
        assertThat(((Number) row.get("numeric_precision")).intValue())
                .as("%s.%s 精度", table, column).isEqualTo(precision);
        assertThat(((Number) row.get("numeric_scale")).intValue())
                .as("%s.%s 小数位", table, column).isEqualTo(scale);
    }

    private void assertUniqueColumns(String table, String expectedColumnsCsv) {
        assertThat(constraintColumns(table, "UNIQUE"))
                .as("%s 应存在唯一约束 (%s)", table, expectedColumnsCsv)
                .contains(expectedColumnsCsv);
    }

    private void assertPrimaryKey(String table, String expectedColumn) {
        assertThat(constraintColumns(table, "PRIMARY KEY"))
                .as("%s 主键应为 %s", table, expectedColumn)
                .containsExactly(expectedColumn);
    }

    private List<String> constraintColumns(String table, String constraintType) {
        return jdbcTemplate.queryForList(
                "SELECT string_agg(kcu.column_name, ',' ORDER BY kcu.ordinal_position)"
                        + " FROM information_schema.table_constraints tc"
                        + " JOIN information_schema.key_column_usage kcu"
                        + "   ON tc.constraint_name = kcu.constraint_name"
                        + "  AND tc.table_schema = kcu.table_schema"
                        + "  AND tc.table_name = kcu.table_name"
                        + " WHERE tc.table_schema = 'public' AND tc.table_name = ? AND tc.constraint_type = ?"
                        + " GROUP BY tc.constraint_name",
                String.class, table, constraintType);
    }
}
