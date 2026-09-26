package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.industry.FundingRound;
import com.portfolio.invest.domain.industry.UnlistedCompany;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
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
 * 未上市策展企业仓储集成测试（照 IndustryWatchRepositoryImplTest 基座）：upsert 幂等语义
 * （命中 industry_code+company_name 更新非键字段）、findByIndustry 排序契约
 * （lastFundingDate DESC NULLS LAST、同日按轮次序倒序）、幂等删除。
 * 测试行业码取 801770（V19 种子占 801080/801150，避开种子行）。
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(UnlistedCompanyRepositoryImpl.class)
class UnlistedCompanyRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private UnlistedCompanyRepository repository;

    private static final String INDUSTRY = "801770";

    private static UnlistedCompany company(String name, FundingRound round, LocalDate lastFundingDate,
                                           BigDecimal totalFundingYi, String segment) {
        return new UnlistedCompany(null, INDUSTRY, name, segment, round, lastFundingDate,
                totalFundingYi, "简介", "来源", Instant.now());
    }

    @DisplayName("findByIndustry 排序：日期倒序 NULLS LAST，同日按轮次序倒序")
    @Test
    void givenCompaniesWithMixedDates_whenFindByIndustry_thenDateDescNullsLastThenRoundDesc() {
        repository.upsert(company("测试甲公司", FundingRound.B, LocalDate.of(2026, 5, 10),
                new BigDecimal("10.00"), "赛道甲"));
        repository.upsert(company("测试乙公司", FundingRound.D, LocalDate.of(2026, 5, 10),
                new BigDecimal("20.00"), "赛道甲")); // 同日更高轮次 → 排前
        repository.upsert(company("测试丙公司", FundingRound.A, LocalDate.of(2026, 8, 1),
                new BigDecimal("30.00"), "赛道甲")); // 最新日期 → 总第一
        repository.upsert(company("测试丁公司", FundingRound.C_PLUS, null,
                null, "赛道甲")); // 无融资日期 → 殿后

        var list = repository.findByIndustry(INDUSTRY);

        assertThat(list).extracting(UnlistedCompany::companyName)
                .containsExactly("测试丙公司", "测试乙公司", "测试甲公司", "测试丁公司");
    }

    @DisplayName("upsert 首插：inserted=true 且计数入账")
    @Test
    void givenNewCompany_whenUpsert_thenInsertedTrueAndCounted() {
        var outcome = repository.upsert(company("测试新公司", FundingRound.A,
                LocalDate.of(2026, 1, 15), new BigDecimal("1.50"), "新赛道"));

        assertThat(outcome.inserted()).isTrue();
        assertThat(repository.countByIndustry(INDUSTRY)).isEqualTo(1);
    }

    @DisplayName("upsert 同幂等键重导：inserted=false、非键字段更新生效且不新增行")
    @Test
    void givenExistingKeyCompany_whenUpsertAgain_thenUpdatedAndNonKeyFieldsReplaced() {
        repository.upsert(company("测试改公司", FundingRound.B, LocalDate.of(2026, 2, 2),
                new BigDecimal("5.00"), "旧赛道"));
        long countBefore = repository.countByIndustry(INDUSTRY);

        var outcome = repository.upsert(company("测试改公司", FundingRound.D, LocalDate.of(2026, 6, 6),
                new BigDecimal("50.00"), "新赛道"));

        assertThat(outcome.inserted()).isFalse();
        assertThat(repository.countByIndustry(INDUSTRY)).isEqualTo(countBefore);
        var updated = repository.findByIndustry(INDUSTRY).get(0);
        assertThat(updated.latestRound()).isEqualTo(FundingRound.D);
        assertThat(updated.lastFundingDate()).isEqualTo(LocalDate.of(2026, 6, 6));
        assertThat(updated.totalFundingYi()).isEqualByComparingTo("50.00");
        assertThat(updated.segment()).isEqualTo("新赛道");
    }

    @DisplayName("deleteById 幂等：删不存在的 id 不抛")
    @Test
    void givenNeverExistedId_whenDeleteById_thenNoThrow() {
        assertThatCode(() -> repository.deleteById(999_999_999L)).doesNotThrowAnyException();
    }
}
