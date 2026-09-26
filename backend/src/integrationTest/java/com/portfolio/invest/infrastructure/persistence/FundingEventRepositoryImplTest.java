package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.FundingEvent;
import com.portfolio.invest.domain.industry.FundingEventRepository;
import com.portfolio.invest.domain.industry.FundingRound;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 融资事件仓储集成测试（与 UnlistedCompanyRepositoryImplTest 同构基座）：upsert 幂等语义
 * （命中 event_date+company_name+round 更新非键字段）、findByIndustrySince 窗口过滤与排序、
 * 幂等删除。测试行业码取 801770（避开 V19 种子的 801080/801150）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(FundingEventRepositoryImpl.class)
class FundingEventRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private FundingEventRepository repository;

    private static final String INDUSTRY = "801770";

    private static FundingEvent event(LocalDate date, String company, FundingRound round, String amountYi) {
        return new FundingEvent(null, date, company, round,
                amountYi == null ? null : new BigDecimal(amountYi), "高瓴",
                INDUSTRY, "测试赛道", "测试来源月报", null, Instant.now());
    }

    @DisplayName("findByIndustrySince：窗口过滤（含边界日）且按 eventDate 倒序")
    @Test
    void givenEventsAcrossDates_whenFindByIndustrySince_thenWindowFilteredAndDateDesc() {
        repository.upsert(event(LocalDate.of(2025, 1, 10), "测试事件甲", FundingRound.A, "1.00"));
        repository.upsert(event(LocalDate.of(2026, 6, 1), "测试事件乙", FundingRound.B, "2.00"));
        repository.upsert(event(LocalDate.of(2026, 6, 1), "测试事件丙", FundingRound.C, "3.00")); // 同日次序稳定（同键不同企不冲突）
        repository.upsert(event(LocalDate.of(2026, 9, 1), "测试事件丁", FundingRound.D, "4.00"));

        var list = repository.findByIndustrySince(INDUSTRY, LocalDate.of(2026, 6, 1));

        assertThat(list).extracting(FundingEvent::companyName)
                .containsExactly("测试事件丁", "测试事件乙", "测试事件丙"); // 窗口外（甲）被过滤
    }

    @DisplayName("upsert 首插：inserted=true")
    @Test
    void givenNewEvent_whenUpsert_thenInsertedTrue() {
        var outcome = repository.upsert(event(LocalDate.of(2026, 3, 3), "测试新事件", FundingRound.SEED, "0.50"));

        assertThat(outcome.inserted()).isTrue();
    }

    @DisplayName("upsert 同幂等键重导：inserted=false、非键字段（金额/投资方）更新生效")
    @Test
    void givenExistingNaturalKeyEvent_whenUpsertAgain_thenUpdatedAndNonKeyFieldsReplaced() {
        repository.upsert(event(LocalDate.of(2026, 4, 4), "测试改事件", FundingRound.B, "1.00"));

        var outcome = repository.upsert(new FundingEvent(null, LocalDate.of(2026, 4, 4),
                "测试改事件", FundingRound.B, new BigDecimal("9.99"), "红杉、高瓴",
                INDUSTRY, "改后赛道", "改后来源", "https://example.com/x", Instant.now()));

        assertThat(outcome.inserted()).isFalse();
        var list = repository.findByIndustrySince(INDUSTRY, LocalDate.of(2026, 1, 1));
        var updated = list.stream().filter(e -> e.companyName().equals("测试改事件")).findFirst().orElseThrow();
        assertThat(updated.amountYi()).isEqualByComparingTo("9.99");
        assertThat(updated.investors()).isEqualTo("红杉、高瓴");
        assertThat(updated.sourceUrl()).isEqualTo("https://example.com/x");
        assertThat(list.stream().filter(e -> e.companyName().equals("测试改事件")).count()).isEqualTo(1); // 不新增行
    }

    @DisplayName("deleteById 幂等：删不存在的 id 不抛")
    @Test
    void givenNeverExistedId_whenDeleteById_thenNoThrow() {
        assertThatCode(() -> repository.deleteById(999_999_999L)).doesNotThrowAnyException();
    }
}
