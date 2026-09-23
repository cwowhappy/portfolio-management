package com.portfolio.invest.infrastructure.persistence;

import com.portfolio.invest.domain.market.FinancialRecord;
import com.portfolio.invest.domain.market.FinancialRecordRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@Import(FinancialRecordRepositoryImpl.class)
class FinancialRecordRepositoryImplTest {

    @ServiceConnection
    static PostgreSQLContainer<?> postgres = PostgresTestSupport.postgres();

    @Autowired
    private FinancialRecordRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private void seed(String date, Double roe, Double roa, Double dta, Double revenue) {
        jdbcTemplate.update(
                "INSERT INTO stock_financial(report_date, stock_code, roe, roa, gross_margin, debt_to_assets, current_ratio, revenue_yoy, netprofit_yoy, revenue) VALUES (?,?,?,?,NULL,NULL,?,NULL,NULL,?)",
                java.sql.Date.valueOf(date), "600519", roe, roa, dta, revenue);
    }

    @DisplayName("按报告期降序取近 N 季，NULL 指标映射 null")
    @Test
    void givenThreeQuarters_whenFindByCodeLatestWithLimit2_thenReturnDescOrder() {
        seed("2025-12-31", 30.0, 20.0, 30.0, 1.7e11);
        seed("2026-03-31", 31.0, null, 31.0, 4.0e10);   // roa 为 NULL
        seed("2026-06-30", 32.0, 21.0, 32.0, 4.2e10);

        var rows = repository.findByCodeLatest("600519", 2);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).reportDate().toString()).isEqualTo("2026-06-30");
        assertThat(rows.get(1).reportDate().toString()).isEqualTo("2026-03-31");
        assertThat(rows.get(1).roa()).isNull();          // NULL → null 非 0.0
        assertThat(rows.get(0).roe()).isEqualTo(32.0);
        assertThat(rows.get(0).revenue()).isEqualByComparingTo("4.2E10");
    }

    @DisplayName("无数据返回空列表")
    @Test
    void givenNoRows_whenFindByCodeLatest_thenReturnEmpty() {
        assertThat(repository.findByCodeLatest("000001", 12)).isEmpty();
    }
}
