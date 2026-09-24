package com.portfolio.invest.application.allocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.allocation.AllocationErrorCode;
import com.portfolio.invest.domain.allocation.AllocationException;
import com.portfolio.invest.domain.allocation.AllocationPlan;
import com.portfolio.invest.domain.allocation.AllocationPlanRepository;
import com.portfolio.invest.domain.allocation.AssetClass;
import com.portfolio.invest.domain.allocation.PlanSource;
import com.portfolio.invest.domain.allocation.RebalanceFrequency;
import com.portfolio.invest.domain.analytics.IndexClosePort;
import com.portfolio.invest.domain.analytics.RiskFreeRatePort;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BacktestApplicationServiceTest {

    private final AllocationPlanRepository repo = mock(AllocationPlanRepository.class);
    private final IndexClosePort indexClose = mock(IndexClosePort.class);
    private final RiskFreeRatePort riskFree = mock(RiskFreeRatePort.class);
    private BacktestApplicationService service;

    @BeforeEach
    void setUp() {
        service = new BacktestApplicationService(repo, indexClose, riskFree);
    }

    private static AllocationPlan plan(String name, Map<AssetClass, BigDecimal> weights) {
        return AllocationPlan.reconstitute(10L, 1L, name, PlanSource.CUSTOM, weights,
                true, Instant.now(), Instant.now(), null, RebalanceFrequency.OFF, null);
    }

    /** 沪深300：2023-01-02→01-03 +10%，01-03→2024-01-03 +10%（首点为基期无收益）。 */
    private static SortedMap<LocalDate, BigDecimal> closes() {
        SortedMap<LocalDate, BigDecimal> m = new TreeMap<>();
        m.put(LocalDate.of(2023, 1, 2), new BigDecimal("100"));
        m.put(LocalDate.of(2023, 1, 3), new BigDecimal("110"));
        m.put(LocalDate.of(2024, 1, 3), new BigDecimal("121"));
        return m;
    }

    /** rf=3.65%（百分数）→ 日频 3.65/100/365 = 0.0001 恰为干净小数。 */
    private static SortedMap<LocalDate, BigDecimal> rf() {
        SortedMap<LocalDate, BigDecimal> m = new TreeMap<>();
        m.put(LocalDate.of(2023, 1, 3), new BigDecimal("3.65"));
        m.put(LocalDate.of(2024, 1, 3), new BigDecimal("3.65"));
        return m;
    }

    @DisplayName("股60/现40 两日+10%：曲线/年化/MDD/夏普与手算对拍")
    @Test
    void givenStock60Cash40_whenBacktest_thenHandCalculatedMetrics() {
        when(repo.findActiveByUserId(1L)).thenReturn(Optional.of(plan("平衡",
                Map.of(AssetClass.STOCK, new BigDecimal("60"), AssetClass.CASH, new BigDecimal("40")))));
        when(indexClose.closes(eq("000300"), any(), any())).thenReturn(closes());
        when(riskFree.oneYearSeries(any(), any())).thenReturn(rf());

        BacktestView view = service.backtest(1L, null, null, "5Y", "never");

        assertThat(view.planName()).isEqualTo("平衡");
        assertThat(view.window()).isEqualTo("5Y");
        assertThat(view.rebalance()).isEqualTo("never");
        // 实际窗口=数据交集（首点=期初 1000，与首日收盘同日期——引擎既定输出约定）
        assertThat(view.windowStart()).isEqualTo("2023-01-03");
        assertThat(view.windowEnd()).isEqualTo("2024-01-03");
        assertThat(view.curve()).hasSize(3);
        assertThat(view.curve().get(0).date()).isEqualTo("2023-01-03");
        assertThat(view.curve().get(0).value()).isEqualTo("1000");
        assertThat(view.curve().get(1).date()).isEqualTo("2023-01-03");
        assertThat(view.curve().get(1).value()).isEqualTo("1060.0400000000");   // 600×1.1 + 400×1.0001
        assertThat(view.curve().get(2).date()).isEqualTo("2024-01-03");
        assertThat(view.curve().get(2).value()).isEqualTo("1126.0800040000");   // 660×1.1 + 400.04×1.0001
        // TWR = 1126.080004/1000 − 1 = 0.126080004；首尾恰好 365 天 → 年化 = TWR
        assertThat(view.annualizedReturn()).isEqualTo("0.1260800040");
        // 曲线单调上行 → 无回撤
        assertThat(view.mdd()).isEqualTo("0.0000000000");
        // 夏普手算 ≈ 606.7664883（手算链见任务报告），容差 0.01
        assertThat(new BigDecimal(view.sharpe()))
                .isCloseTo(new BigDecimal("606.7664883"),
                        org.assertj.core.data.Offset.offset(new BigDecimal("0.01")));
        assertThat(view.rfFallback()).isFalse();
        // 权重 0 的资产不取数
        verify(indexClose, never()).closes(eq("H11001"), any(), any());
        verify(indexClose, never()).closes(eq("518880"), any(), any());
    }

    @DisplayName("REITS 权重>0 → REITS_BACKTEST_UNSUPPORTED（先于取数）")
    @Test
    void givenReitsPlan_whenBacktest_thenUnsupported() {
        when(repo.findByIdAndUserId(10L, 1L)).thenReturn(Optional.of(plan("含REITs",
                Map.of(AssetClass.STOCK, new BigDecimal("50"), AssetClass.REITS, new BigDecimal("50")))));

        assertThatThrownBy(() -> service.backtest(1L, 10L, null, "5Y", "never"))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.REITS_BACKTEST_UNSUPPORTED));
    }

    @DisplayName("无 planId/template 且无激活方案 → NO_ACTIVE_PLAN")
    @Test
    void givenNoPlanNoTemplateNoActive_whenBacktest_thenNoActivePlan() {
        when(repo.findActiveByUserId(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.backtest(1L, null, null, "5Y", "never"))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.NO_ACTIVE_PLAN));
    }

    @DisplayName("template 参数 → 模板权重与显示名；MAX 窗口自 2006-01-01 起取数")
    @Test
    void givenTemplate_whenBacktest_thenTemplateWeights() {
        when(indexClose.closes(eq("000300"), any(), any())).thenReturn(closes());
        when(indexClose.closes(eq("H11001"), any(), any())).thenReturn(closes());
        when(indexClose.closes(eq("518880"), any(), any())).thenReturn(closes());
        when(riskFree.oneYearSeries(any(), any())).thenReturn(rf());

        BacktestView view = service.backtest(1L, null, "PERMANENT_PORTFOLIO", "MAX", "quarterly");

        assertThat(view.planName()).isEqualTo("永久组合");
        assertThat(view.window()).isEqualTo("MAX");
        assertThat(view.rebalance()).isEqualTo("quarterly");
        assertThat(view.windowStart()).isEqualTo("2023-01-03");
        // 永久组合 25/25/25/25，三类价格资产均 +10%：3×250×1.1 + 250×1.0001
        assertThat(view.curve().get(1).value()).isEqualTo("1075.0250000000");
        // 3×275×1.1 + 250.025×1.0001
        assertThat(view.curve().get(2).value()).isEqualTo("1157.5500025000");
        verify(indexClose).closes(eq("518880"), eq(LocalDate.of(2006, 1, 1)), any());
    }

    @DisplayName("非法 template → INVALID_INPUT")
    @Test
    void givenUnknownTemplate_whenBacktest_thenInvalidInput() {
        assertThatThrownBy(() -> service.backtest(1L, null, "NO_SUCH", "5Y", "never"))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.INVALID_INPUT));
    }

    @DisplayName("planId 不存在/非本人 → NOT_FOUND")
    @Test
    void givenMissingPlan_whenBacktest_thenNotFound() {
        when(repo.findByIdAndUserId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.backtest(1L, 99L, null, "5Y", "never"))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.NOT_FOUND));
    }

    @DisplayName("某价格资产窗口内收盘<2点 → INVALID_INPUT（先于引擎 NSEE 拦截）")
    @Test
    void givenSingleClose_whenBacktest_thenInvalidInput() {
        when(repo.findActiveByUserId(1L)).thenReturn(Optional.of(plan("全股",
                Map.of(AssetClass.STOCK, new BigDecimal("100")))));
        SortedMap<LocalDate, BigDecimal> single = new TreeMap<>();
        single.put(LocalDate.of(2023, 1, 3), new BigDecimal("100"));
        when(indexClose.closes(eq("000300"), any(), any())).thenReturn(single);
        when(riskFree.oneYearSeries(any(), any())).thenReturn(new TreeMap<>());

        assertThatThrownBy(() -> service.backtest(1L, null, null, "5Y", "never"))
                .isInstanceOfSatisfying(AllocationException.class,
                        e -> assertThat(e.code()).isEqualTo(AllocationErrorCode.INVALID_INPUT));
    }
}
