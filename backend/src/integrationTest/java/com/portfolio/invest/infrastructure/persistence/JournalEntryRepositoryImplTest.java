package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.domain.journal.PeriodType;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

// @DataJpaTest 切片 + 真实 PG：继承 PostgresTestSupport 复用 testFixtures 的 JVM 单例容器（@DynamicPropertySource）。
// Boot 4 的 @DataJpaTest 不含 Flyway 自动配置（schema 由 Flyway 管），需 @ImportAutoConfiguration 显式引入；
// RepositoryImpl 适配器不在切片扫描范围内，用 @Import 显式装配。
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(JournalEntryRepositoryImpl.class)
class JournalEntryRepositoryImplTest extends PostgresTestSupport {

    @Autowired
    private JournalEntryRepositoryImpl repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** journal_entry.user_id 外键引用 app_user(id)，需先植入 id=1/2 的用户行。 */
    @BeforeEach
    void seedUsers() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                1L, "journal-t5-1", "h", "USER", "PENDING");
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                2L, "journal-t5-2", "h", "USER", "PENDING");
    }

    private static JournalEntry buyMemo(Long userId) {
        return JournalEntry.create(userId, JournalEntryType.BUY_MEMO, "600519", "贵州茅台", 10L,
                "买入茅台", "理由", new BigDecimal("1800"), new BigDecimal("1500"),
                null, null, null, LocalDate.of(2026, 9, 2), Instant.now());
    }

    @Test
    void 保存记录并回读全部字段() {
        JournalEntry saved = repository.save(buyMemo(1L));

        assertThat(saved.id()).isNotNull();
        var found = repository.findByIdAndUserId(saved.id(), 1L).orElseThrow();
        assertThat(found.type()).isEqualTo(JournalEntryType.BUY_MEMO);
        assertThat(found.stockCode()).isEqualTo("600519");
        assertThat(found.stockName()).isEqualTo("贵州茅台");
        assertThat(found.tradeId()).isEqualTo(10L);
        assertThat(found.targetPrice()).isEqualByComparingTo("1800");
        assertThat(found.stopLoss()).isEqualByComparingTo("1500");
        assertThat(found.eventDate()).isEqualTo(LocalDate.of(2026, 9, 2));
    }

    @Test
    void 按类型过滤() {
        repository.save(buyMemo(1L));
        repository.save(JournalEntry.create(1L, JournalEntryType.REVIEW, null, null, null,
                "Q3 复盘", "内容", null, null,
                PeriodType.QUARTERLY, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 9, 30), Instant.now()));

        assertThat(repository.findByUserId(1L, JournalEntryType.BUY_MEMO)).hasSize(1);
        assertThat(repository.findByUserId(1L, JournalEntryType.REVIEW)).hasSize(1);
        assertThat(repository.findByUserId(1L, null)).hasSize(2);
    }

    @Test
    void 用户隔离() {
        repository.save(buyMemo(1L));
        assertThat(repository.findByUserId(2L, null)).isEmpty();
        assertThat(repository.findByIdAndUserId(repository.findByUserId(1L, null).get(0).id(), 2L)).isEmpty();
    }

    @Test
    void 更新后替换原记录() {
        JournalEntry saved = repository.save(buyMemo(1L));
        repository.save(saved.update("600519", "贵州茅台", 99L, "新标题", "新内容",
                new BigDecimal("2000"), new BigDecimal("1600"), null, null, null,
                LocalDate.of(2026, 9, 3)));

        var found = repository.findByIdAndUserId(saved.id(), 1L).orElseThrow();
        assertThat(found.title()).isEqualTo("新标题");
        assertThat(found.tradeId()).isEqualTo(99L);
        assertThat(found.targetPrice()).isEqualByComparingTo("2000");
        assertThat(found.eventDate()).isEqualTo(LocalDate.of(2026, 9, 3));
    }

    @Test
    void 删除记录() {
        JournalEntry saved = repository.save(buyMemo(1L));
        repository.deleteById(saved.id());
        assertThat(repository.findByIdAndUserId(saved.id(), 1L)).isEmpty();
    }
}
