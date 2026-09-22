package com.portfolio.invest.application.market;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.market.FinancialIndicator;
import com.portfolio.invest.domain.market.FinancialRecord;
import com.portfolio.invest.domain.market.FinancialRecordRepository;
import com.portfolio.invest.domain.market.Financials;
import com.portfolio.invest.domain.market.MarketDataException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FinancialQueryServiceTest {

    private final FinancialRecordRepository repository = mock(FinancialRecordRepository.class);
    private final MarketDataService market = mock(MarketDataService.class);
    private final FinancialQueryService service = new FinancialQueryService(repository, market);

    private FinancialRecord record(String date, Double roe, Double roa, Double dta) {
        return new FinancialRecord(LocalDate.parse(date), roe, roa, 40.0, dta, 2.0, 10.0, 12.0,
                new BigDecimal("4.2E10"));
    }

    @DisplayName("两源合并：净利率取 live 最新期，杜邦因子标注库表报告期")
    @Test
    void givenRecordsAndLive_whenAnalyze_thenMergesBothSources() {
        when(repository.findByCodeLatest("600519", 12))
                .thenReturn(List.of(record("2026-06-30", 32.0, 21.0, 32.0)));
        when(market.financials("600519")).thenReturn(new Financials("600519", "贵州茅台", 25.0, 8.0,
                List.of(new FinancialIndicator("2026-06-30", 23.0, 160.0, 4.2e10, 1.05e10, 32.0, 91.0))));

        var view = service.analyze("600519", 12);

        assertThat(view.records()).hasSize(1);
        assertThat(view.live().name()).isEqualTo("贵州茅台");
        assertThat(view.duPont().netMargin()).isEqualTo(1.05e10 / 4.2e10); // 0.25
        assertThat(view.duPont().recordReportDate()).isEqualTo("2026-06-30");
        assertThat(view.duPont().liveReportDate()).isEqualTo("2026-06-30");
    }

    @DisplayName("东财 live 不可用：live=null，杜邦净利率缺失继续部分拆解")
    @Test
    void givenLiveUnavailable_whenAnalyze_thenDegradesToRecordsOnly() {
        when(repository.findByCodeLatest("600519", 12))
                .thenReturn(List.of(record("2026-06-30", 32.0, 21.0, 32.0)));
        when(market.financials("600519")).thenThrow(new MarketDataException("UPSTREAM", "超时"));

        var view = service.analyze("600519", 12);

        assertThat(view.live()).isNull();
        assertThat(view.duPont().netMargin()).isNull();
        assertThat(view.duPont().missingFactors()).contains("净利率");
        assertThat(view.duPont().equityMultiplier()).isCloseTo(1.0 / 0.68, within(1e-9)); // dta=32%
    }

    @DisplayName("库表无记录：duPont=null（工具层降级提示）")
    @Test
    void givenNoRecords_whenAnalyze_thenDupontNull() {
        when(repository.findByCodeLatest("000001", 12)).thenReturn(List.of());
        when(market.financials("000001")).thenThrow(new MarketDataException("UPSTREAM", "无数据"));

        var view = service.analyze("000001", 12);

        assertThat(view.records()).isEmpty();
        assertThat(view.duPont()).isNull();
    }
}
