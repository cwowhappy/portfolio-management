package com.portfolio.invest.infrastructure.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 评测独立 schema 供给器纯逻辑契约（MS-30 B2，设计规格 §2.3）：守卫断言只放行 currentSchema
 * 首位为 {@code eval_schema} 的 URL（防连错库——EvalRunner 覆盖项被优先级压掉时静默连上
 * dev/主库的初跑事故防线延续；末位 public 供 pg_trgm 扩展对象解析，属 V3 迁移硬前提）；
 * 返回 URL 的 currentSchema 参数构造须精确无歧义。
 * 真实 PG 上的 DROP/CREATE 与 Flyway 端到端行为见 integrationTest 的
 * {@code EvalPostgresProvisionerIntegrationTest}（test 源集禁 Docker 的分层边界）。
 */
class EvalPostgresProvisionerTest {

    @Test
    @DisplayName("守卫拒绝不含 currentSchema 参数的 URL")
    void whenGuardUrlWithoutCurrentSchema_thenRejects() {
        assertThatThrownBy(() -> EvalPostgresProvisioner.assertGuard("jdbc:postgresql://localhost:5432/invest"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("eval_schema");
    }

    @Test
    @DisplayName("守卫拒绝 null URL")
    void whenGuardNullUrl_thenRejects() {
        assertThatThrownBy(() -> EvalPostgresProvisioner.assertGuard(null))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("守卫拒绝指向其他 schema 的 currentSchema 参数")
    void whenGuardUrlPointsToOtherSchema_thenRejects() {
        assertThatThrownBy(() -> EvalPostgresProvisioner.assertGuard(
                "jdbc:postgresql://localhost:5432/invest?currentSchema=public"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("守卫按首位段精确匹配——前缀撞名（eval_schema2）不放行")
    void whenGuardUrlHasPrefixCollidingSchemaName_thenRejects() {
        // 子串包含式判断会放过 eval_schema2 这类前缀撞名（实际指向另一个 schema），须按段精确匹配
        assertThatThrownBy(() -> EvalPostgresProvisioner.assertGuard(
                "jdbc:postgresql://localhost:5432/invest?currentSchema=eval_schema2"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("守卫拒绝 eval_schema 不居首位的 search_path（非限定建表会落错 schema）")
    void whenGuardSearchPathNotLedByEvalSchema_thenRejects() {
        assertThatThrownBy(() -> EvalPostgresProvisioner.assertGuard(
                "jdbc:postgresql://localhost:5432/invest?currentSchema=public,eval_schema"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("守卫放行 currentSchema 首位为 eval_schema 的 URL（含 public 扩展解析段）")
    void whenGuardUrlLedByEvalSchema_thenPasses() {
        assertThatCode(() -> EvalPostgresProvisioner.assertGuard(
                "jdbc:postgresql://localhost:5432/invest?currentSchema=eval_schema"))
                .doesNotThrowAnyException();
        assertThatCode(() -> EvalPostgresProvisioner.assertGuard(
                "jdbc:postgresql://localhost:5432/test?loggerLevel=OFF&currentSchema=eval_schema,public"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("裸 URL 构造评测 URL——以问号追加评测 search_path")
    void whenBuildEvalUrlFromBareUrl_thenAppendsWithQuestionMark() {
        assertThat(EvalPostgresProvisioner.evalJdbcUrl("jdbc:postgresql://localhost:5432/invest"))
                .isEqualTo("jdbc:postgresql://localhost:5432/invest?currentSchema=eval_schema,public");
    }

    @Test
    @DisplayName("已带查询参数的 URL——以 & 追加（Testcontainers URL 形态）")
    void whenBuildEvalUrlWithExistingParams_thenAppendsWithAmpersand() {
        assertThat(EvalPostgresProvisioner.evalJdbcUrl(
                        "jdbc:postgresql://localhost:32768/test?loggerLevel=OFF"))
                .isEqualTo("jdbc:postgresql://localhost:32768/test?loggerLevel=OFF&currentSchema=eval_schema,public");
    }

    @Test
    @DisplayName("已携带 currentSchema 的 URL——替换而非叠加（防 search_path 歧义）")
    void whenBuildEvalUrlWithExistingCurrentSchema_thenReplacedNotDuplicated() {
        assertThat(EvalPostgresProvisioner.evalJdbcUrl(
                        "jdbc:postgresql://localhost:5432/invest?currentSchema=public&socketTimeout=60"))
                .isEqualTo("jdbc:postgresql://localhost:5432/invest?socketTimeout=60&currentSchema=eval_schema,public");
    }

    @Test
    @DisplayName("空/blank URL 直接拒绝（fail-fast 引导）")
    void whenBuildEvalUrlFromBlank_thenRejects() {
        assertThatThrownBy(() -> EvalPostgresProvisioner.evalJdbcUrl(" "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EVAL_DATASOURCE_URL");
    }
}
