package com.portfolio.invest.infrastructure.eval;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测独立 schema 供给器（MS-30 B2，设计规格 §2.3「同库独立 schema」）：评测子进程在
 * Spring 上下文启动前经 JDBC 直连对目标库执行 {@code DROP SCHEMA IF EXISTS eval_schema
 * CASCADE; CREATE SCHEMA eval_schema;}——每轮重置复原容器级干净度，替代已退役的
 * Testcontainers 路径（部署机无 Docker，EvalPostgresSupport 不可用）。
 *
 * <p>隔离机制是连接级单旋钮：返回的 URL 携带 {@code currentSchema=eval_schema,public}，
 * 后续 Flyway 全量迁移（V1~V5）/JPA/JdbcTemplate 的非限定名全随 search_path 首位落评测
 * schema——全仓 DDL 非限定的侦察事实使此方案零额外配置。末位追加 {@code public} 是
 * V3 pg_trgm 的硬前提：PG JDBC 的 currentSchema 会整体替换 search_path，库级扩展装在
 * public 的算子类（gin_trgm_ops）必须仍在路径上才可被 V3 的非限定 CREATE INDEX 解析
 * （首跑冒烟实证：缺 public 即 Migration V3 失败）。eval_schema 恒居首位，隔离语义不变；
 * 主库其他 schema 的数据不受影响，生产上下文不带该参数零影响。
 *
 * <p>归位 main 源集 infrastructure 包（而非 eval 源集）：无业务依赖、纯 java.sql，
 * ArchUnit 分层合规；eval 源集单向引用 main 的既有边界不变。 {@link #assertGuard}
 * 是 datasource 覆盖项的兜底校验（防优先级回归静默连上 dev/主库，初跑事故教训延续）。
 */
public final class EvalPostgresProvisioner {

    /** 评测专用 schema 名：URL 参数值与 DDL 标识符共用（小写字面量，防拼错漂移）。 */
    public static final String EVAL_SCHEMA = "eval_schema";

    private static final String CURRENT_SCHEMA_PARAM = "currentSchema";

    /** 评测 search_path：eval_schema 首位（非限定对象全落评测 schema）+ public（pg_trgm 扩展对象解析）。 */
    private static final String EVAL_SEARCH_PATH = EVAL_SCHEMA + ",public";

    private EvalPostgresProvisioner() {}

    /**
     * 重置评测 schema 并产出评测 JDBC URL：DROP CASCADE 清残留（上一轮跑过的 65 表）后
     * CREATE，再把评测 search_path 附加到 URL（已携带的旧 currentSchema 替换——
     * 防 search_path 歧义）。连接失败（库不可达/凭据错误）抛 {@link IllegalStateException}，
     * 由调用方给引导性退出。
     */
    public static String reset(String baseUrl, String username, String password) {
        requireBaseUrl(baseUrl);
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + EVAL_SCHEMA + " CASCADE");
            statement.execute("CREATE SCHEMA " + EVAL_SCHEMA);
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "评测库 schema 重置失败（" + baseUrl + "）: " + e.getMessage(), e);
        }
        return evalJdbcUrl(baseUrl);
    }

    /**
     * 守卫断言：URL 的 currentSchema 参数值首位须为 {@code eval_schema}，否则抛
     * {@link IllegalStateException}——评测上下文的 datasource 必须指向独立评测 schema
     * （防连错库：覆盖项被 application.yml 压掉时连上 dev/主库即 fail-fast）。
     * 按首位段精确匹配而非子串包含（前缀撞名如 eval_schema2 不放行；eval_schema
     * 不居首位的 search_path 会让非限定建表落错 schema，同样不放行）。
     */
    public static void assertGuard(String url) {
        if (!carriesEvalCurrentSchema(url)) {
            throw new IllegalStateException(
                    "评估 datasource 未指向独立评测 schema（currentSchema 首位须为 " + EVAL_SCHEMA
                            + "）: " + url
                            + "——覆盖项可能被优先级压掉，防连错 dev/主库");
        }
    }

    /** 评测 URL 构造（纯函数，供测试直测）：剥掉既有 currentSchema 参数后以正确分隔符附加评测 search_path。 */
    static String evalJdbcUrl(String baseUrl) {
        requireBaseUrl(baseUrl);
        int queryStart = baseUrl.indexOf('?');
        String base = queryStart >= 0 ? baseUrl.substring(0, queryStart) : baseUrl;
        List<String> params = new ArrayList<>();
        if (queryStart >= 0) {
            for (String param : baseUrl.substring(queryStart + 1).split("&")) {
                String key = param.contains("=") ? param.substring(0, param.indexOf('=')) : param;
                if (!CURRENT_SCHEMA_PARAM.equals(key)) params.add(param);
            }
        }
        params.add(CURRENT_SCHEMA_PARAM + "=" + EVAL_SEARCH_PATH);
        return base + "?" + String.join("&", params);
    }

    /** currentSchema 首位段是否精确等于评测 schema（逗号分隔 search_path 形态）。 */
    private static boolean carriesEvalCurrentSchema(String url) {
        if (url == null) return false;
        int queryStart = url.indexOf('?');
        if (queryStart < 0) return false;
        for (String param : url.substring(queryStart + 1).split("&")) {
            int eq = param.indexOf('=');
            if (eq > 0 && CURRENT_SCHEMA_PARAM.equals(param.substring(0, eq))) {
                String value = param.substring(eq + 1);
                int comma = value.indexOf(',');
                return EVAL_SCHEMA.equals(comma < 0 ? value : value.substring(0, comma));
            }
        }
        return false;
    }

    private static void requireBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalStateException(
                    "评测库 JDBC URL 为空——请 export EVAL_DATASOURCE_URL=<url>（或仓库根 .env 配置），"
                            + "缺省回退主 datasource 的 SPRING_DATASOURCE_URL");
        }
    }
}
