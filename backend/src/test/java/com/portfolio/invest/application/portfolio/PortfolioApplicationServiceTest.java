package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioErrorCode;
import com.portfolio.invest.domain.portfolio.PortfolioException;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.portfolio.Position;
import com.portfolio.invest.domain.portfolio.Trade;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PortfolioApplicationServiceTest {

    private final PortfolioRepository repo = mock(PortfolioRepository.class);
    private final MarketDataService market = mock(MarketDataService.class);
    private final ValuationRepository valuation = mock(ValuationRepository.class);
    private PortfolioApplicationService service;

    @BeforeEach
    void setUp() {
        service = new PortfolioApplicationService(repo, market, valuation);
        when(repo.findPortfolioByUserId(1L)).thenReturn(Optional.of(Portfolio.reconstitute(10L, 1L,
                com.portfolio.invest.domain.portfolio.CostMethod.WEIGHTED_AVG, Instant.now(), Instant.now())));
    }

    @Test
    void 分组列表按组合返回() {
        when(repo.findGroupsByPortfolioId(10L)).thenReturn(List.of(
                HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));

        var groups = service.groups(1L);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).name()).isEqualTo("华泰");
    }

    @Test
    void 删除非空分组抛异常() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.findPositionsByGroupId(1L)).thenReturn(List.of(
                com.portfolio.invest.domain.portfolio.Position.create(10L, 1L, "600519", "贵州茅台", Instant.now())));

        assertThatThrownBy(() -> service.deleteGroup(1L, 1L))
                .isInstanceOf(PortfolioException.class)
                .hasMessageContaining("先清空");
    }

    @Test
    void 创建分组成功() {
        when(repo.saveGroup(any())).thenAnswer(inv -> inv.getArgument(0));
        var view = service.createGroup(1L, new CreateGroupCommand("华泰", GroupType.ACCOUNT));
        assertThat(view.name()).isEqualTo("华泰");
    }

    @Test
    void 访问非本人分组抛NOT_FOUND() {
        when(repo.findGroupByIdAndPortfolioId(1L, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteGroup(1L, 1L))
                .isInstanceOfSatisfying(PortfolioException.class,
                        e -> assertThat(e.code()).isEqualTo(PortfolioErrorCode.NOT_FOUND));
    }

    @Test
    void 买入不存在持仓时新建() {
        when(repo.findPositionByPortfolioIdAndGroupIdAndStockCode(10L, 1L, "600519")).thenReturn(Optional.empty());
        when(repo.findGroupByIdAndPortfolioId(1L, 10L))
                .thenReturn(Optional.of(HoldingGroup.reconstitute(1L, 10L, "华泰", GroupType.ACCOUNT, Instant.now())));
        when(repo.savePosition(any())).thenAnswer(inv -> {
            Position p = inv.getArgument(0);
            return Position.reconstitute(
                    99L, p.portfolioId(), p.groupId(), p.stockCode(), p.stockName(),
                    p.quantity(), p.costBasis(), p.totalBuyCost(), p.cumulativeCashDividend(),
                    p.realizedPnl(), p.netCashFlow(), p.createdAt(), p.updatedAt());
        });
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.buy(1L, new BuyCommand(1L, "600519", "贵州茅台",
                LocalDate.of(2026, 8, 27), new BigDecimal("1500"),
                new BigDecimal("100"), new BigDecimal("5")));

        assertThat(view.id()).isEqualTo(99L);
        assertThat(view.stockCode()).isEqualTo("600519");
        assertThat(view.quantity()).isEqualByComparingTo("100");
        assertThat(view.avgCost()).isEqualByComparingTo("1500.05");

        ArgumentCaptor<Trade> captor = ArgumentCaptor.forClass(Trade.class);
        verify(repo).saveTrade(captor.capture());
        assertThat(captor.getValue().positionId()).isEqualTo(99L);
    }

    @Test
    void 卖出调用引擎并保存() {
        Position pos = Position.create(10L, 1L, "600519", "贵州茅台", Instant.now())
                .applyBuy(new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("0"));
        when(repo.findPositionByIdAndPortfolioId(5L, 10L)).thenReturn(Optional.of(pos));
        when(repo.savePosition(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repo.saveTrade(any())).thenAnswer(inv -> inv.getArgument(0));

        var view = service.sell(1L, new SellCommand(5L, LocalDate.of(2026, 8, 28),
                new BigDecimal("120"), new BigDecimal("40"), new BigDecimal("0")));

        assertThat(view.quantity()).isEqualByComparingTo("60");
        assertThat(view.realizedPnl()).isEqualByComparingTo("800");
    }
}
