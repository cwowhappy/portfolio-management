package com.portfolio.invest.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.portfolio.invest.domain.intelligence.PageQuery;
import com.portfolio.invest.domain.intelligence.PageResult;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * 动态 WHERE 装配器（2026-10-15 第四轮审查 persistence 誊写簇归一）：条件短语 append +
 * 占位 args 依序收集，日界折算（Asia/Shanghai {@code dayStart}/{@code dayEnd}）与 D20
 * 分页双查询内置。
 *
 * <p>归一对象：IntelligenceNews/Announcement/Policy/DailyBrief 四仓库 search 的
 * 「{@code StringBuilder where + List<Object> args} 逐字段 null/blank 装配 + COUNT /
 * OFFSET-LIMIT 双查询」誊写簇（原每加一个过滤字段誊写 4 份）。用户输入绝不拼接进
 * SQL 文本——全部经 {@code ?} 占位参数绑定；{@code field}/{@code base} 入参只允许
 * 编译期常量（表名/列名/固定短语），调用方不得传用户输入。
 *
 * <p><b>不变式（行为红线）</b>：{@link #sql()} 产出短语与 {@link #args()} 参数序列
 * 必须与链式调用顺序逐字等价于原逐字段装配——条件追加顺序即参数顺序；改动本类
 * 或调用链顺序前，先读 {@code NewsRepositoryTest}/{@code AnnouncementRepositoryTest}/
 * {@code PolicyRepositoryTest}（integrationTest）钉住的查询行为。
 */
public final class WhereClauseBuilder {

    /** 市场时区（日界折算口径，与调度任务 zone 一致）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final StringBuilder where;
    private final List<Object> args = new ArrayList<>();

    private WhereClauseBuilder(String base) {
        this.where = new StringBuilder(base);
    }

    /** 以 {@code " WHERE true"} 为锚起手（动态 AND 拼接锚点，照 ScreeningRepositoryImpl 先例）。 */
    public static WhereClauseBuilder whereTrue() {
        return new WhereClauseBuilder(" WHERE true");
    }

    /** 以自定义基础短语起手（编译期常量，如 {@code " WHERE e.status = 'SUCCESS'"}）。 */
    public static WhereClauseBuilder where(String base) {
        return new WhereClauseBuilder(base);
    }

    /**
     * 文本 trgm 双臂检索：keyword 非 null 非 blank 时追加
     * {@code AND (field LIKE ? OR field % ?)}——LIKE 保底子串命中 + trgm 容错近似
     * （两算子均被 GIN trgm 索引加速）；参数 {@code %trim%}、{@code trim}。
     */
    public WhereClauseBuilder andIfKeyword(String field, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            String trimmed = keyword.trim();
            where.append(" AND (").append(field).append(" LIKE ? OR ").append(field).append(" % ?)");
            args.add("%" + trimmed + "%");
            args.add(trimmed);
        }
        return this;
    }

    /** 等值过滤：value 非 null 非 blank 时追加 {@code AND field = ?}，参数为 trim 后值。 */
    public WhereClauseBuilder andIfNotBlank(String field, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(field).append(" = ?");
            args.add(value.trim());
        }
        return this;
    }

    /** 比较过滤：value 非 null 时追加 {@code AND field op ?}（op 如 {@code =}、{@code >=}）。 */
    public WhereClauseBuilder andIfNotNull(String field, String operator, Object value) {
        if (value != null) {
            where.append(" AND ").append(field).append(' ').append(operator).append(" ?");
            args.add(value);
        }
        return this;
    }

    /** 等值过滤：value 非 null 时追加 {@code AND field = ?}。 */
    public WhereClauseBuilder andIfNotNull(String field, Object value) {
        if (value != null) {
            where.append(" AND ").append(field).append(" = ?");
            args.add(value);
        }
        return this;
    }

    /** 等值过滤（枚举专用重载）：value 非 null 时追加 {@code AND field = ?}，参数为其 name。 */
    public WhereClauseBuilder andIfNotNull(String field, Enum<?> value) {
        if (value != null) {
            where.append(" AND ").append(field).append(" = ?");
            args.add(value.name());
        }
        return this;
    }

    /**
     * SQL IN 下推：values 非 null 非空时追加 {@code AND field IN (?,?,…)}——占位符逐值
     * 展开、参数依序绑定（用户输入绝不拼接进 SQL 文本；空列表由调用方归一 null 不过滤，
     * 此处再兜一次）。
     */
    public WhereClauseBuilder andIn(String field, List<?> values) {
        if (values != null && !values.isEmpty()) {
            String placeholders = String.join(",", values.stream().map(v -> "?").toList());
            where.append(" AND ").append(field).append(" IN (").append(placeholders).append(")");
            args.addAll(values);
        }
        return this;
    }

    /**
     * JSONB 数组包含（单字符串元素）：value 非 null 非 blank 时追加
     * {@code AND field @> ?::jsonb}，参数为 {@code [trim]} 的 JSON 文本。
     */
    public WhereClauseBuilder andIfJsonbContains(String field, String value) {
        if (value != null && !value.isBlank()) {
            where.append(" AND ").append(field).append(" @> ?::jsonb");
            args.add(toJson(List.of(value.trim())));
        }
        return this;
    }

    /** JSONB 数组包含（单枚举名元素）：value 非 null 时行为同 {@link #andIfJsonbContains(String, String)}。 */
    public WhereClauseBuilder andIfJsonbContains(String field, Enum<?> value) {
        if (value != null) {
            where.append(" AND ").append(field).append(" @> ?::jsonb");
            args.add(toJson(List.of(value.name())));
        }
        return this;
    }

    /**
     * timestamptz 日界下界：date 非 null 时追加 {@code AND field >= ?}，参数为该日
     * Asia/Shanghai 零点（UTC {@link OffsetDateTime}）。
     */
    public WhereClauseBuilder andDayStart(String field, LocalDate date) {
        if (date != null) {
            where.append(" AND ").append(field).append(" >= ?");
            args.add(dayStart(date));
        }
        return this;
    }

    /**
     * timestamptz 日界上界（闭区间右端）：date 非 null 时追加 {@code AND field < ?}，
     * 参数为<b>次日</b>零点——次日起始即排除点。
     */
    public WhereClauseBuilder andDayEnd(String field, LocalDate date) {
        if (date != null) {
            where.append(" AND ").append(field).append(" < ?");
            args.add(dayStart(date.plusDays(1)));
        }
        return this;
    }

    /** DATE 列日界下界（日期直比）：date 非 null 时追加 {@code AND field >= ?}，参数为 {@link Date}。 */
    public WhereClauseBuilder andDateStart(String field, LocalDate date) {
        if (date != null) {
            where.append(" AND ").append(field).append(" >= ?");
            args.add(Date.valueOf(date));
        }
        return this;
    }

    /** DATE 列日界上界（闭区间右端即含）：date 非 null 时追加 {@code AND field <= ?}，参数为 {@link Date}。 */
    public WhereClauseBuilder andDateEnd(String field, LocalDate date) {
        if (date != null) {
            where.append(" AND ").append(field).append(" <= ?");
            args.add(Date.valueOf(date));
        }
        return this;
    }

    /** 装配出的 WHERE 短语（含基础锚点，可直接拼进 SQL）。 */
    public String sql() {
        return where.toString();
    }

    /** 与占位符顺序一致的参数列表（副本，调用方可安全追加分页参数）。 */
    public List<Object> args() {
        return new ArrayList<>(args);
    }

    /**
     * D20 分页双查询：{@code SELECT count(*)} 独立计 total（空归 0），条目查询按
     * {@code orderBy} 排序 + {@code LIMIT ? OFFSET ?}——分页参数接在过滤参数之后。
     * {@code orderBy} 为不含 ORDER BY 关键字的排序短语（编译期常量，如
     * {@code "r.published_at DESC, r.id DESC"}）。
     */
    public static <T> PageResult<T> paged(JdbcTemplate jdbc, String selectCols, String fromClause,
            String orderBy, RowMapper<T> rowMapper, PageQuery q, WhereClauseBuilder where) {
        Long total = jdbc.queryForObject(
                "SELECT count(*)" + fromClause + where.sql(), Long.class, where.args().toArray());
        List<Object> pageArgs = where.args();
        pageArgs.add(q.pageSize());
        pageArgs.add((long) (q.page() - 1) * q.pageSize());
        List<T> items = jdbc.query(
                selectCols + fromClause + where.sql() + " ORDER BY " + orderBy + " LIMIT ? OFFSET ?",
                rowMapper, pageArgs.toArray());
        return new PageResult<>(items, total == null ? 0 : total, q.page(), q.pageSize());
    }

    // ── 日界折算（原三 impl 逐字誊写的 dayStart 收敛于此） ──────────

    /** LocalDate 当日零点（Asia/Shanghai）→ Instant → OffsetDateTime（PG timestamptz 参数绑定）。 */
    public static OffsetDateTime dayStart(LocalDate day) {
        return offsetDateTimeOf(day.atStartOfDay(ZONE).toInstant());
    }

    /** Instant → UTC OffsetDateTime（PG timestamptz 参数绑定）。 */
    public static OffsetDateTime offsetDateTimeOf(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("JSONB 参数序列化失败", e);
        }
    }
}
