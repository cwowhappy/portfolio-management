package com.portfolio.invest.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.intelligence.AnnouncementType;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
import com.portfolio.invest.domain.intelligence.PolicyDirection;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * {@link WhereClauseBuilder} 装配契约（纯单测，无 Spring/DB）：钉死行为红线——
 * 产出 SQL 短语与参数序列须与四仓库（News/Announcement/Policy/DailyBrief）search
 * 原逐字段装配逐字等价（integrationTest 三仓库测试钉查询行为，本类钉装配骨架）。
 *
 * <p>覆盖：锚点起手（WHERE true / 自定义基础短语）、各条件族的可空跳过语义、
 * trgm 双臂/JSONB 包含/IN 展开/原样条件短语（子查询形态）的参数形态、Asia/Shanghai
 * 日界折算（UTC OffsetDateTime 与 java.sql.Date 两口径）、链式顺序与参数顺序一致、
 * paged 双查询 SQL 与参数序。
 */
class WhereClauseBuilderTest {

    @Test
    @DisplayName("给定无条件，when whereTrue 起手，then仅基础锚点且无参数")
    void givenNoConditions_whenWhereTrue_thenBaseAnchorOnly() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue();

        assertThat(where.sql()).isEqualTo(" WHERE true");
        assertThat(where.args()).isEmpty();
    }

    @Test
    @DisplayName("给定自定义基础短语，when where 起手，then基础短语原样保留（政策 SUCCESS 形态）")
    void givenCustomBase_whenWhere_thenBasePreserved() {
        WhereClauseBuilder where = WhereClauseBuilder.where(" WHERE e.status = 'SUCCESS'")
                .andIfKeyword("r.title", "降息");

        assertThat(where.sql())
                .isEqualTo(" WHERE e.status = 'SUCCESS' AND (r.title LIKE ? OR r.title % ?)");
        assertThat(where.args()).containsExactly("%降息%", "降息");
    }

    @Test
    @DisplayName("给定 null/blank/空集 值，when各条件族追加，then全部跳过仅余锚点")
    void givenNullBlankEmptyValues_whenAppended_thenAllSkipped() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIfKeyword("r.title", null)
                .andIfKeyword("r.title", "   ")
                .andIfNotBlank("r.stock_code", null)
                .andIfNotBlank("r.stock_code", "  ")
                .andIfNotNull("e.importance", ">=", null)
                .andIfNotNull("e.direction", (PolicyDirection) null)
                .andIn("r.stock_code", null)
                .andIn("r.stock_code", List.of())
                .andIfJsonbContains("e.stock_codes", (String) null)
                .andIfJsonbContains("e.stock_codes", " ")
                .andIfJsonbContains("e.ann_types", (AnnouncementType) null)
                .andDayStart("r.published_at", null)
                .andDayEnd("r.published_at", null)
                .andDateStart("trade_date", null)
                .andDateEnd("trade_date", null);

        assertThat(where.sql()).isEqualTo(" WHERE true");
        assertThat(where.args()).isEmpty();
    }

    @Test
    @DisplayName("给定非blank关键词，when andIfKeyword，then trgm双臂短语与 %trim%/trim 参数")
    void givenKeyword_whenAndIfKeyword_thenTrgmDoubleArmWithTrimmedArgs() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIfKeyword("r.title", " 政策利率 ");

        assertThat(where.sql()).isEqualTo(" WHERE true AND (r.title LIKE ? OR r.title % ?)");
        assertThat(where.args()).containsExactly("%政策利率%", "政策利率");
    }

    @Test
    @DisplayName("给定非blank值，when andIfNotBlank，then等值短语与trim后参数")
    void givenNonBlankValue_whenAndIfNotBlank_thenEqualityWithTrim() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIfNotBlank("r.stock_code", " 600519 ");

        assertThat(where.sql()).isEqualTo(" WHERE true AND r.stock_code = ?");
        assertThat(where.args()).containsExactly("600519");
    }

    @Test
    @DisplayName("给定非null值，when andIfNotNull，then比较符短语/枚举name/布尔原值按形态落参")
    void givenNonNullValues_whenAndIfNotNull_thenOperatorAndEnumAndBooleanForms() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIfNotNull("e.importance", ">=", 80)
                .andIfNotNull("e.direction", PolicyDirection.EASING)
                .andIfNotNull("r.major", Boolean.TRUE);

        assertThat(where.sql()).isEqualTo(
                " WHERE true AND e.importance >= ? AND e.direction = ? AND r.major = ?");
        assertThat(where.args()).containsExactly(80, "EASING", Boolean.TRUE);
    }

    @Test
    @DisplayName("给定非null值，when andIfNotNullPhrase，then原样短语追加且参数绑定占位符（Screening 指数成分子查询形态）")
    void givenNonNullValue_whenAndIfNotNullPhrase_thenRawConditionAppendedVerbatim() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIfNotNullPhrase(
                        "d.stock_code IN (SELECT stock_code FROM index_constituent WHERE index_code = ?)",
                        "000300");

        assertThat(where.sql()).isEqualTo(
                " WHERE true AND d.stock_code IN (SELECT stock_code FROM index_constituent WHERE index_code = ?)");
        assertThat(where.args()).containsExactly("000300");
    }

    @Test
    @DisplayName("给定null值，when andIfNotNullPhrase，then短语跳过且无参数")
    void givenNullValue_whenAndIfNotNullPhrase_thenSkipped() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIfNotNullPhrase(
                        "d.stock_code IN (SELECT stock_code FROM index_constituent WHERE index_code = ?)",
                        null);

        assertThat(where.sql()).isEqualTo(" WHERE true");
        assertThat(where.args()).isEmpty();
    }

    @Test
    @DisplayName("给定非空集合，when andIn，then占位符逐值展开且参数依序绑定；null/空集跳过")
    void givenValues_whenAndIn_thenPlaceholdersExpandedInOrder() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIn("r.stock_code", List.of("600519", "300750"));

        assertThat(where.sql()).isEqualTo(" WHERE true AND r.stock_code IN (?,?)");
        assertThat(where.args()).containsExactly("600519", "300750");
    }

    @Test
    @DisplayName("给定字符串/枚举值，when andIfJsonbContains，then JSONB包含短语且参数为单元素JSON数组")
    void givenStringAndEnum_whenAndIfJsonbContains_thenSingleElementJsonArrayArg() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIfJsonbContains("e.stock_codes", " 600519 ")
                .andIfJsonbContains("e.ann_types", AnnouncementType.BUYBACK);

        assertThat(where.sql()).isEqualTo(
                " WHERE true AND e.stock_codes @> ?::jsonb AND e.ann_types @> ?::jsonb");
        assertThat(where.args()).containsExactly("[\"600519\"]", "[\"BUYBACK\"]");
    }

    @Test
    @DisplayName("给定日期，when andDayStart/andDayEnd，then Asia/Shanghai零点折算为UTC OffsetDateTime（上界为次日零点）")
    void givenDates_whenAndDayStartEnd_thenAsiaShanghaiMidnightAsUtcOffsetDateTime() {
        LocalDate from = LocalDate.of(2026, 9, 28);
        LocalDate to = LocalDate.of(2026, 9, 29);
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andDayStart("r.published_at", from)
                .andDayEnd("r.published_at", to);

        assertThat(where.sql()).isEqualTo(
                " WHERE true AND r.published_at >= ? AND r.published_at < ?");
        // Asia/Shanghai 零点 = 前一日 16:00 UTC（中国无 DST）；to 闭区间右端 = 次日零点排除点
        assertThat(where.args()).containsExactly(
                OffsetDateTime.of(2026, 9, 27, 16, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 9, 29, 16, 0, 0, 0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("给定日期，when andDateStart/andDateEnd，then DATE直比闭区间且参数为java.sql.Date")
    void givenDates_whenAndDateStartEnd_thenSqlDateClosedRange() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andDateStart("trade_date", LocalDate.of(2026, 9, 28))
                .andDateEnd("trade_date", LocalDate.of(2026, 9, 29));

        assertThat(where.sql()).isEqualTo(" WHERE true AND trade_date >= ? AND trade_date <= ?");
        assertThat(where.args()).containsExactly(
                Date.valueOf("2026-09-28"), Date.valueOf("2026-09-29"));
    }

    @Test
    @DisplayName("给定多条件链，when取sql与args，then短语拼接顺序与参数顺序逐字一致")
    void givenChainedConditions_whenSqlAndArgs_thenOrderMatchesAppendOrder() {
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIfKeyword("r.title", "政策")
                .andIfNotBlank("r.stock_code", "600519")
                .andIn("r.stock_code", List.of("300750"))
                .andIfJsonbContains("e.industry_codes", "801250")
                .andDayStart("r.published_at", LocalDate.of(2026, 9, 28))
                .andDayEnd("r.published_at", LocalDate.of(2026, 9, 29))
                .andIfNotNull("e.importance", ">=", 80);

        assertThat(where.sql()).isEqualTo(" WHERE true"
                + " AND (r.title LIKE ? OR r.title % ?)"
                + " AND r.stock_code = ?"
                + " AND r.stock_code IN (?)"
                + " AND e.industry_codes @> ?::jsonb"
                + " AND r.published_at >= ?"
                + " AND r.published_at < ?"
                + " AND e.importance >= ?");
        assertThat(where.args()).containsExactly(
                "%政策%", "政策",
                "600519",
                "300750",
                "[\"801250\"]",
                OffsetDateTime.of(2026, 9, 27, 16, 0, 0, 0, ZoneOffset.UTC),
                OffsetDateTime.of(2026, 9, 29, 16, 0, 0, 0, ZoneOffset.UTC),
                80);
    }

    @Test
    @DisplayName("给定分页查询，when paged，then COUNT与条目SQL逐字等价且分页参数接在过滤参数之后")
    @SuppressWarnings("unchecked")
    void givenPagedQuery_whenPaged_thenCountAndPageSqlWithPageArgsAppended() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(7L);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        WhereClauseBuilder where = WhereClauseBuilder.whereTrue()
                .andIfNotBlank("a", "v");
        PageQuery q = new PageQuery(3, 20, null, null, null, null, null, null);

        PageResult<String> page = WhereClauseBuilder.paged(
                jdbc, "SELECT cols", " FROM t", "x DESC", (rs, i) -> "row", q, where);

        ArgumentCaptor<String> countSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> countArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForObject(countSql.capture(), eq(Long.class), countArgs.capture());
        assertThat(countSql.getValue()).isEqualTo("SELECT count(*) FROM t WHERE true AND a = ?");
        assertThat(countArgs.getValue()).containsExactly("v");

        ArgumentCaptor<String> pageSql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> pageArgs = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).query(pageSql.capture(), any(RowMapper.class), pageArgs.capture());
        assertThat(pageSql.getValue())
                .isEqualTo("SELECT cols FROM t WHERE true AND a = ? ORDER BY x DESC LIMIT ? OFFSET ?");
        assertThat(pageArgs.getValue()).containsExactly("v", 20, 40L);

        assertThat(page.total()).isEqualTo(7);
        assertThat(page.items()).isEmpty();
        assertThat(page.page()).isEqualTo(3);
        assertThat(page.pageSize()).isEqualTo(20);
    }

    @Test
    @DisplayName("给定COUNT返回null，when paged，then total归0（原双查询空值兜底语义）")
    @SuppressWarnings("unchecked")
    void givenNullTotal_whenPaged_thenTotalFallsBackToZero() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());

        PageResult<String> page = WhereClauseBuilder.paged(jdbc, "SELECT cols", " FROM t", "x DESC",
                (rs, i) -> "row", PageQuery.of(1, 20), WhereClauseBuilder.whereTrue());

        assertThat(page.total()).isZero();
    }

    @Test
    @DisplayName("给定Instant，when dayStart/offsetDateTimeOf，then UTC偏移量参数绑定口径不变")
    void givenInstant_whenDayStartAndOffsetDateTimeOf_thenUtcBindingUnchanged() {
        assertThat(WhereClauseBuilder.dayStart(LocalDate.of(2026, 9, 28)))
                .isEqualTo(OffsetDateTime.of(2026, 9, 27, 16, 0, 0, 0, ZoneOffset.UTC));
        assertThat(WhereClauseBuilder.offsetDateTimeOf(java.time.Instant.parse("2026-10-09T01:25:00Z")))
                .isEqualTo(OffsetDateTime.parse("2026-10-09T01:25:00Z"));
    }
}
