package com.portfolio.invest.application.valuation;

import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import com.portfolio.invest.domain.valuation.TreasuryYield;
import com.portfolio.invest.domain.valuation.IndexValuation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ValuationApplicationServiceTest {

    private final ValuationRepository repo = mock(ValuationRepository.class);
    private final ValuationApplicationService service = new ValuationApplicationService(repo);

    @Test
    void overview在无快照时返回空数据状态() {
        when(repo.findLatestSnapshot()).thenReturn(null);
        when(repo.findAllSnapshots()).thenReturn(List.of());
        when(repo.findAllTreasuryYields()).thenReturn(List.of());

        var view = service.overview();

        assertThat(view.latestSnapshot()).isNull();
        assertThat(view.dataAccumulating()).isTrue();
    }

    @Test
    void overview计算全A中位数分位() {
        when(repo.findLatestSnapshot()).thenReturn(new ValuationSnapshot(
                LocalDate.of(2026, 8, 27), new BigDecimal("19.14"), new BigDecimal("1.68"), 220, new BigDecimal("0.0410")));
        when(repo.findAllSnapshots()).thenReturn(List.of(
                new ValuationSnapshot(LocalDate.of(2026, 8, 25), new BigDecimal("18.52"), new BigDecimal("1.63"), 245, new BigDecimal("0.0456")),
                new ValuationSnapshot(LocalDate.of(2026, 8, 26), new BigDecimal("18.90"), new BigDecimal("1.66"), 231, new BigDecimal("0.0431"))));
        when(repo.findAllTreasuryYields()).thenReturn(List.of());
        when(repo.findIndexValuations("000300")).thenReturn(List.of());

        var view = service.overview();

        assertThat(view.latestSnapshot().peMedian()).isEqualByComparingTo("19.14");
        // 历史 18.52、18.90 均小于 19.14 → 100%
        assertThat(view.pePercentile()).isEqualByComparingTo("100.00");
    }

    @Test
    void overview计算ERP() {
        when(repo.findLatestSnapshot()).thenReturn(null);
        when(repo.findAllSnapshots()).thenReturn(List.of());
        when(repo.findAllTreasuryYields()).thenReturn(List.of(new TreasuryYield(LocalDate.of(2026, 8, 27), new BigDecimal("2.21"))));
        when(repo.findIndexValuations("000300")).thenReturn(List.of(
                new IndexValuation(LocalDate.of(2026, 8, 27), "000300", "沪深300", new BigDecimal("12.8"), new BigDecimal("1.42"), new BigDecimal("2.35"))));

        var view = service.overview();

        // ERP = 沪深300 股息率 2.35 − 10y 国债 2.21 = 0.14
        assertThat(view.erp()).isEqualByComparingTo("0.14");
    }
}
