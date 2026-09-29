package com.portfolio.invest.application.journal;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.invest.domain.journal.JournalEntry;
import com.portfolio.invest.domain.journal.JournalEntryRepository;
import com.portfolio.invest.domain.journal.JournalEntryType;
import com.portfolio.invest.support.PostgresTestSupport;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * RESEARCH_EVENT 时间线并入回归：timeline() 本身不改逻辑，journal 源全量拉取后按类型映射，
 * 集成测试验证研究事件条目自动出现在时间线（类型映射 + JOURNAL 来源）。
 */
@SpringBootTest
class ResearchEventTimelineIntegrationTest extends PostgresTestSupport {

    /** 高位哨兵 id，避开其他集成测试占用的用户段（journal 仓库测试用 51/52，组合并用 9001+）。 */
    private static final long USER_ID = 9051L;

    @Autowired
    private JournalApplicationService journalService;

    @Autowired
    private JournalEntryRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** journal_entry.user_id 外键引用 app_user(id)，需先植入用户行。 */
    @BeforeEach
    void seedUser() {
        jdbcTemplate.update(
                "INSERT INTO app_user(id, username, password_hash, role, status) VALUES (?, ?, ?, ?, ?)",
                USER_ID, "journal-research-timeline", "h", "USER", "PENDING");
    }

    @AfterEach
    void cleanup() {
        jdbcTemplate.update("DELETE FROM journal_entry WHERE user_id = ?", USER_ID);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", USER_ID);
    }

    @DisplayName("研究事件保存后自动出现在时间线")
    @Test
    void givenResearchEventEntry_whenTimeline_thenEventIncluded() {
        JournalEntry saved = repository.save(JournalEntry.create(USER_ID, JournalEntryType.RESEARCH_EVENT,
                "600519", "贵州茅台", null, "立项茅台研究", "触发条件：跌破估值区间下沿",
                null, null, null, null, null, LocalDate.of(2026, 9, 2), Instant.now(), 77L));

        var events = journalService.timeline(USER_ID, null, null);

        assertThat(events).anySatisfy(e -> {
            assertThat(e.type()).isEqualTo(TimelineEventType.RESEARCH_EVENT);
            assertThat(e.date()).isEqualTo(LocalDate.of(2026, 9, 2));
            assertThat(e.title()).isEqualTo("立项茅台研究");
            assertThat(e.stockCode()).isEqualTo("600519");
            assertThat(e.refId()).isEqualTo(saved.id());
            assertThat(e.refType()).isEqualTo("JOURNAL");
        });
    }

    @DisplayName("时间线日期过滤对研究事件同样生效")
    @Test
    void givenResearchEventOutsideRange_whenTimeline_thenEventExcluded() {
        repository.save(JournalEntry.create(USER_ID, JournalEntryType.RESEARCH_EVENT,
                "600519", "贵州茅台", null, "立项茅台研究", "触发条件：跌破估值区间下沿",
                null, null, null, null, null, LocalDate.of(2026, 9, 2), Instant.now(), 77L));

        var events = journalService.timeline(USER_ID,
                LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));

        assertThat(events).noneMatch(e -> e.type() == TimelineEventType.RESEARCH_EVENT);
    }
}
