package com.portfolio.invest.application.portfolio;

import com.portfolio.invest.application.market.MarketDataService;
import com.portfolio.invest.domain.portfolio.GroupType;
import com.portfolio.invest.domain.portfolio.HoldingGroup;
import com.portfolio.invest.domain.portfolio.Portfolio;
import com.portfolio.invest.domain.portfolio.PortfolioException;
import com.portfolio.invest.domain.portfolio.PortfolioRepository;
import com.portfolio.invest.domain.valuation.ValuationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
}
