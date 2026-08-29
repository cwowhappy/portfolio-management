package com.portfolio.invest.application.valuation;

import com.portfolio.invest.domain.valuation.ValuationRepository;
import com.portfolio.invest.domain.valuation.ValuationSnapshot;
import com.portfolio.invest.domain.valuation.TreasuryYield;
import com.portfolio.invest.domain.valuation.IndexValuation;
import com.portfolio.invest.domain.valuation.IndustryValuation;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

    @Test
    void overview满数据时计算温度计且非积累期() {
        List<ValuationSnapshot> snapshots = List.of(
                new ValuationSnapshot(LocalDate.of(2026, 8, 21), new BigDecimal("18.00"), new BigDecimal("1.50"), 200, new BigDecimal("0.0300")),
                new ValuationSnapshot(LocalDate.of(2026, 8, 24), new BigDecimal("18.50"), new BigDecimal("1.55"), 210, new BigDecimal("0.0350")),
                new ValuationSnapshot(LocalDate.of(2026, 8, 25), new BigDecimal("18.80"), new BigDecimal("1.60"), 215, new BigDecimal("0.0380")),
                new ValuationSnapshot(LocalDate.of(2026, 8, 26), new BigDecimal("19.00"), new BigDecimal("1.65"), 218, new BigDecimal("0.0400")),
                new ValuationSnapshot(LocalDate.of(2026, 8, 27), new BigDecimal("19.20"), new BigDecimal("1.68"), 220, new BigDecimal("0.0420")));
        when(repo.findLatestSnapshot()).thenReturn(snapshots.get(4));
        when(repo.findAllSnapshots()).thenReturn(snapshots);
        when(repo.findAllTreasuryYields()).thenReturn(List.of(
                new TreasuryYield(LocalDate.of(2026, 8, 27), new BigDecimal("2.21"))));
        when(repo.findIndexValuations("000300")).thenReturn(List.of(
                new IndexValuation(LocalDate.of(2026, 8, 27), "000300", "沪深300", new BigDecimal("12.8"), new BigDecimal("1.42"), new BigDecimal("2.35"))));
        when(repo.findIndexValuations("000016")).thenReturn(List.of(
                new IndexValuation(LocalDate.of(2026, 8, 27), "000016", "上证50", new BigDecimal("10.0"), new BigDecimal("1.20"), new BigDecimal("3.0"))));

        var view = service.overview();

        // 5 个快照 → 非积累期
        assertThat(view.dataAccumulating()).isFalse();
        // PE 分位 80.00、ERP 分位 0.00、破净分位 80.00 → 温度计 = 80*0.4 + 100*0.4 + 20*0.2 = 76
        assertThat(view.pePercentile()).isNotNull();
        assertThat(view.erpPercentile()).isNotNull();
        assertThat(view.thermometer()).isEqualByComparingTo("76");
        // 五个指数：000016/000300 有数据，其余空 → 非空分支与空分支均覆盖
        assertThat(view.indices()).hasSize(5);
        assertThat(view.indices().get(1).indexCode()).isEqualTo("000300");
    }

    @Test
    void overview历史含空值时过滤且不抛NPE() {
        when(repo.findLatestSnapshot()).thenReturn(new ValuationSnapshot(
                LocalDate.of(2026, 8, 27), new BigDecimal("19.14"), new BigDecimal("1.68"), 220, new BigDecimal("0.0410")));
        when(repo.findAllSnapshots()).thenReturn(List.of(
                new ValuationSnapshot(LocalDate.of(2026, 8, 25), new BigDecimal("18.52"), new BigDecimal("1.63"), 245, new BigDecimal("0.0456")),
                new ValuationSnapshot(LocalDate.of(2026, 8, 26), new BigDecimal("18.90"), new BigDecimal("1.66"), 231, new BigDecimal("0.0431"))));
        when(repo.findAllTreasuryYields()).thenReturn(List.of(
                new TreasuryYield(LocalDate.of(2026, 8, 27), new BigDecimal("2.21"))));
        // 沪深300：中间元素 dividendYield 为 null → 旧代码 ERP 分位会 NPE
        when(repo.findIndexValuations("000300")).thenReturn(List.of(
                new IndexValuation(LocalDate.of(2026, 8, 25), "000300", "沪深300", new BigDecimal("12.5"), new BigDecimal("1.40"), new BigDecimal("2.30")),
                new IndexValuation(LocalDate.of(2026, 8, 26), "000300", "沪深300", new BigDecimal("12.8"), new BigDecimal("1.42"), null),
                new IndexValuation(LocalDate.of(2026, 8, 27), "000300", "沪深300", new BigDecimal("13.0"), new BigDecimal("1.45"), new BigDecimal("2.35"))));
        // 上证50：中间元素 pe/pb 为 null → 旧代码指数分位会 NPE
        when(repo.findIndexValuations("000016")).thenReturn(List.of(
                new IndexValuation(LocalDate.of(2026, 8, 25), "000016", "上证50", new BigDecimal("10.0"), new BigDecimal("1.20"), new BigDecimal("3.0")),
                new IndexValuation(LocalDate.of(2026, 8, 26), "000016", "上证50", null, null, new BigDecimal("3.1")),
                new IndexValuation(LocalDate.of(2026, 8, 27), "000016", "上证50", new BigDecimal("10.5"), new BigDecimal("1.25"), new BigDecimal("3.2"))));

        var view = service.overview();

        // 不抛异常，且非空值仍被用于分位计算
        assertThat(view.erp()).isEqualByComparingTo("0.14");
        assertThat(view.erpPercentile()).isEqualByComparingTo("50.00");
        assertThat(view.indices()).hasSize(5);
        assertThat(view.indices().get(0).pePercentile()).isEqualByComparingTo("50.00"); // 000016
        assertThat(view.indices().get(1).pePercentile()).isEqualByComparingTo("66.67"); // 000300
    }

    @Test
    void industries空仓库返回空列表() {
        when(repo.findLatestSnapshot()).thenReturn(null);

        assertThat(service.industries("pe")).isEmpty();
        assertThat(service.industries(null)).isEmpty();
    }

    @Test
    void industries按指标排序且空值排最后() {
        LocalDate day = LocalDate.of(2026, 8, 27);
        when(repo.findLatestSnapshot()).thenReturn(new ValuationSnapshot(
                day, new BigDecimal("19.14"), new BigDecimal("1.68"), 220, new BigDecimal("0.0410")));
        when(repo.findIndustryValuationsByDay(day)).thenReturn(List.of(
                new IndustryValuation(day, "801010", "农林牧渔", new BigDecimal("25.0"), new BigDecimal("2.5"), new BigDecimal("8.0"), new BigDecimal("1.2")),
                new IndustryValuation(day, "801030", "基础化工", new BigDecimal("15.0"), new BigDecimal("1.5"), new BigDecimal("12.0"), new BigDecimal("2.0")),
                new IndustryValuation(day, "801120", "食品饮料", null, null, null, null)));

        var byPe = service.industries("pe");
        assertThat(byPe).hasSize(3);
        assertThat(byPe.get(0).industryCode()).isEqualTo("801030"); // 15.0 最小在前
        assertThat(byPe.get(1).industryCode()).isEqualTo("801010");
        assertThat(byPe.get(2).industryCode()).isEqualTo("801120"); // null 排最后

        var byPb = service.industries("pb");
        assertThat(byPb.get(0).industryCode()).isEqualTo("801030"); // 1.5 < 2.5
        assertThat(byPb.get(2).industryCode()).isEqualTo("801120");

        var byRoe = service.industries("roe");
        assertThat(byRoe.get(0).industryCode()).isEqualTo("801010"); // 8.0 < 12.0
        assertThat(byRoe.get(2).industryCode()).isEqualTo("801120");

        var byDividend = service.industries("dividend");
        assertThat(byDividend.get(0).industryCode()).isEqualTo("801010"); // 1.2 < 2.0
        assertThat(byDividend.get(2).industryCode()).isEqualTo("801120");
    }

    @Test
    void history返回各序列() {
        List<ValuationSnapshot> snapshots = List.of(new ValuationSnapshot(
                LocalDate.of(2026, 8, 27), new BigDecimal("19.14"), new BigDecimal("1.68"), 220, new BigDecimal("0.0410")));
        List<TreasuryYield> yields = List.of(new TreasuryYield(LocalDate.of(2026, 8, 27), new BigDecimal("2.21")));
        List<IndexValuation> indices = List.of(new IndexValuation(
                LocalDate.of(2026, 8, 27), "000300", "沪深300", new BigDecimal("12.8"), new BigDecimal("1.42"), new BigDecimal("2.35")));
        when(repo.findAllSnapshots()).thenReturn(snapshots);
        when(repo.findAllTreasuryYields()).thenReturn(yields);
        when(repo.findIndexValuations("000300")).thenReturn(indices);

        var view = service.history();

        assertThat(view.snapshots()).isSameAs(snapshots);
        assertThat(view.treasuryYields()).isSameAs(yields);
        assertThat(view.indexValuations()).isSameAs(indices);
    }

    @Test
    void 同交易日内多次overview命中缓存仅查询一次快照表() {
        when(repo.findLatestSnapshot()).thenReturn(null);
        when(repo.findAllSnapshots()).thenReturn(List.of());
        when(repo.findAllTreasuryYields()).thenReturn(List.of());

        service.overview();
        service.overview();
        service.overview();

        verify(repo, times(1)).findAllSnapshots();
        verify(repo, times(1)).findLatestSnapshot();
    }

    @Test
    void 同交易日内多次history命中缓存仅查询一次() {
        when(repo.findAllSnapshots()).thenReturn(List.of());
        when(repo.findAllTreasuryYields()).thenReturn(List.of());
        when(repo.findIndexValuations("000300")).thenReturn(List.of());

        service.history();
        service.history();

        verify(repo, times(1)).findAllSnapshots();
    }

    @Test
    void 缓存过期后重新查询() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(0);
        ValuationApplicationService clocked = new ValuationApplicationService(repo, now::get);
        when(repo.findLatestSnapshot()).thenReturn(null);
        when(repo.findAllSnapshots()).thenReturn(List.of());
        when(repo.findAllTreasuryYields()).thenReturn(List.of());

        clocked.overview();
        now.addAndGet(5 * 60 * 1000 + 1); // 超过 5 分钟 TTL
        clocked.overview();

        verify(repo, times(2)).findAllSnapshots();
    }
}
