package com.portfolio.invest.application.wiki;

import com.portfolio.invest.domain.wiki.WikiEntryType;
import com.portfolio.invest.support.PostgresTestSupport;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOP 模板 seeding 集成测试（真实 PG，@SpringBootTest 照 application 包并发先例：
 * 服务调用各走独立事务真实提交，跨事务验证幂等）。
 *
 * <p>口径：F01 定稿 50 条（10/17/11/12）；CONCEPT seeding 不受扰动；
 * SOP 标记独立列 sop_seeded_at（老用户已 seed 概念不复用行存在性判定）。
 */
@SpringBootTest
class WikiSopSeedingIntegrationTest extends PostgresTestSupport {

    @Autowired
    private WikiApplicationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long user;

    /** wiki_entry / wiki_seed_state 外键引用 app_user，先植用户行（唯一用户名隔离，@AfterEach 逆序清理）。 */
    private Long seedUser() {
        return jdbcTemplate.queryForObject(
                "INSERT INTO app_user(username, password_hash, role, status) VALUES (?, 'x', 'USER', 'APPROVED') RETURNING id",
                Long.class, "sop_seed_" + System.nanoTime());
    }

    @AfterEach
    void cleanup() {
        if (user != null) {
            jdbcTemplate.update("DELETE FROM wiki_entry WHERE user_id = ?", user);
            jdbcTemplate.update("DELETE FROM wiki_seed_state WHERE user_id = ?", user);
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", user);
        }
    }

    private Integer countByType(Long userId, String type) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM wiki_entry WHERE user_id = ? AND type = ?", Integer.class, userId, type);
    }

    @DisplayName("老用户（概念标记已在）首次拉取研究笔记：50 条 SOP_TEMPLATE + 置 sop_seeded_at，二次/删光后零新增")
    @Test
    void givenConceptSeededUser_whenListResearchNotes_thenSeedOnceAndNeverResurrect() {
        user = seedUser();
        // 老用户形态：概念已 seed（行已在、sop_seeded_at 为 null），概念条目可能早已删光
        jdbcTemplate.update("INSERT INTO wiki_seed_state(user_id, seeded_at) VALUES (?, now())", user);

        List<WikiEntryView> first = service.entries(user, WikiEntryType.RESEARCH_NOTE);
        assertThat(first).hasSize(50);
        assertThat(first).allSatisfy(v -> {
            assertThat(v.category()).isEqualTo("SOP_TEMPLATE");
            assertThat(v.title()).startsWith("SOP·");
        });
        assertThat(first.stream().filter(v -> v.title().startsWith("SOP·新分析·"))).hasSize(10);
        assertThat(first.stream().filter(v -> v.title().startsWith("SOP·制定投资策略·"))).hasSize(17);
        assertThat(first.stream().filter(v -> v.title().startsWith("SOP·建仓与持仓·"))).hasSize(11);
        assertThat(first.stream().filter(v -> v.title().startsWith("SOP·复盘·"))).hasSize(12);
        assertThat(countByType(user, "CONCEPT")).isZero(); // 概念零扰动（不动概念 seeding）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT sop_seeded_at IS NOT NULL FROM wiki_seed_state WHERE user_id = ?", Boolean.class, user))
                .isTrue();

        // 二次拉取（独立事务）：零新增
        assertThat(service.entries(user, WikiEntryType.RESEARCH_NOTE)).hasSize(50);
        assertThat(countByType(user, "RESEARCH_NOTE")).isEqualTo(50);

        // 删光全部模板后：标记不随条目删除，零复活
        first.forEach(v -> service.deleteEntry(user, v.id()));
        assertThat(service.entries(user, WikiEntryType.RESEARCH_NOTE)).isEmpty();
        assertThat(countByType(user, "RESEARCH_NOTE")).isZero();
    }

    @DisplayName("概念先行用户：SOP seeding 后概念列表仍 10 条不重复，SOP 条目不混入概念")
    @Test
    void givenConceptFirstUser_whenSopSeeded_thenConceptListUndisturbed() {
        user = seedUser();
        assertThat(service.entries(user, WikiEntryType.CONCEPT)).hasSize(10); // 触发概念 seeding
        assertThat(service.entries(user, WikiEntryType.RESEARCH_NOTE)).hasSize(50); // 触发 SOP seeding

        assertThat(service.entries(user, WikiEntryType.CONCEPT)).hasSize(10); // 仍 10 条，无重复 seed
        assertThat(countByType(user, "CONCEPT")).isEqualTo(10);
        assertThat(service.entries(user, WikiEntryType.RESEARCH_NOTE)).hasSize(50);
    }

    @DisplayName("全新用户研究笔记先行：置 SOP 标记前概念预置同步补齐（seeded_at 不谎报），后续概念访问零新增")
    @Test
    void givenBrandNewUser_whenResearchNotesFirst_thenConceptsAlsoSeeded() {
        user = seedUser();
        assertThat(service.entries(user, WikiEntryType.RESEARCH_NOTE)).hasSize(50);

        assertThat(countByType(user, "CONCEPT")).isEqualTo(10); // 标记行落库前概念已真实 seed
        assertThat(service.entries(user, WikiEntryType.CONCEPT)).hasSize(10); // 后续概念访问不重复 seed
        assertThat(countByType(user, "CONCEPT")).isEqualTo(10);
    }
}
