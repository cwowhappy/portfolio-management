package com.portfolio.invest.application.screening;

import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningException;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreeningApplicationServiceTest {

    private final ScreeningRepository repo = mock(ScreeningRepository.class);
    private final ScreeningApplicationService service = new ScreeningApplicationService(repo);

    private ScreeningCriteria criteria(BigDecimal peMax) {
        return new ScreeningCriteria(peMax, null, null, null, null, null, null, null,
                null, null, null, null, null, "pe_ttm", SortDirection.ASC, 200);
    }

    @Test
    void 空条件抛出异常() {
        assertThatThrownBy(() -> service.screen(criteria(null)))
                .isInstanceOf(ScreeningException.class)
                .hasMessageContaining("至少需要一个筛选条件");
    }

    @Test
    void 非法排序字段抛出异常() {
        var c = new ScreeningCriteria(new BigDecimal("20"), null, null, null, null, null,
                null, null, null, null, null, null, null, "bogus", SortDirection.ASC, 200);
        assertThatThrownBy(() -> service.screen(c))
                .isInstanceOf(ScreeningException.class)
                .hasMessageContaining("不支持的排序字段");
    }

    @Test
    void 上限越界抛出异常() {
        var c = new ScreeningCriteria(new BigDecimal("20"), null, null, null, null, null,
                null, null, null, null, null, null, null, "pe_ttm", SortDirection.ASC, 500);
        assertThatThrownBy(() -> service.screen(c))
                .isInstanceOf(ScreeningException.class)
                .hasMessageContaining("结果上限");
    }

    @Test
    void 合法条件委托仓库() {
        var c = criteria(new BigDecimal("20"));
        when(repo.findStocks(c)).thenReturn(List.of(
                new StockScreeningResult("601398", "工商银行", "801780", "银行",
                        new BigDecimal("5.6"), new BigDecimal("0.62"), new BigDecimal("5.4"),
                        new BigDecimal("11.8"), null, null, null, null, null, null, null, null)));
        var results = service.screen(c);
        assertThat(results).hasSize(1);
        verify(repo).findStocks(c);
    }
}
