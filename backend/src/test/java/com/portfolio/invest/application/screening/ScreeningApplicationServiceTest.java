package com.portfolio.invest.application.screening;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.domain.screening.ScreeningCriteria;
import com.portfolio.invest.domain.screening.ScreeningErrorCode;
import com.portfolio.invest.domain.screening.ScreeningException;
import com.portfolio.invest.domain.screening.ScreeningRepository;
import com.portfolio.invest.domain.screening.SortDirection;
import com.portfolio.invest.domain.screening.StockScreeningResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScreeningApplicationServiceTest {

    private final ScreeningRepository repo = mock(ScreeningRepository.class);
    private final ApplicationCache cache = mock(ApplicationCache.class);
    private final ScreeningApplicationService service = new ScreeningApplicationService(repo, cache);

    private ScreeningCriteria criteria(BigDecimal peMax) {
        return new ScreeningCriteria(peMax, null, null, null, null, null, null, null,
                null, null, null, null, null, "pe_ttm", SortDirection.ASC, 200);
    }

    @DisplayName("空条件抛出异常")
    @Test
    void emptyConditionThrowsException() {
        ScreeningException ex = catchThrowableOfType(() -> service.screen(criteria(null)), ScreeningException.class);
        assertThat(ex).isNotNull().hasMessageContaining("至少需要一个筛选条件");
        assertThat(ex.code()).isEqualTo(ScreeningErrorCode.NO_CONDITION);
    }

    @DisplayName("非法排序字段抛出异常")
    @Test
    void invalidSortFieldThrowsException() {
        var c = new ScreeningCriteria(new BigDecimal("20"), null, null, null, null, null,
                null, null, null, null, null, null, null, "bogus", SortDirection.ASC, 200);
        ScreeningException ex = catchThrowableOfType(() -> service.screen(c), ScreeningException.class);
        assertThat(ex).isNotNull().hasMessageContaining("不支持的排序字段");
        assertThat(ex.code()).isEqualTo(ScreeningErrorCode.INVALID_SORT);
    }

    @DisplayName("上限越界抛出异常")
    @Test
    void limitOutOfRangeThrowsException() {
        var c = new ScreeningCriteria(new BigDecimal("20"), null, null, null, null, null,
                null, null, null, null, null, null, null, "pe_ttm", SortDirection.ASC, 500);
        ScreeningException ex = catchThrowableOfType(() -> service.screen(c), ScreeningException.class);
        assertThat(ex).isNotNull().hasMessageContaining("结果上限");
        assertThat(ex.code()).isEqualTo(ScreeningErrorCode.INVALID_LIMIT);
    }

    @DisplayName("合法条件委托仓库")
    @Test
    void validConditionDelegatesToRepository() {
        var c = criteria(new BigDecimal("20"));
        when(repo.findStocks(c)).thenReturn(List.of(
                new StockScreeningResult("601398", "工商银行", "801780", "银行",
                        new BigDecimal("5.6"), new BigDecimal("0.62"), new BigDecimal("5.4"),
                        new BigDecimal("11.8"), null, null, null, null, null, null, null, null)));
        var results = service.screen(c);
        assertThat(results).hasSize(1);
        verify(repo).findStocks(c);
    }

    @DisplayName("相同条件命中缓存不再查仓库")
    @Test
    void sameConditionCacheHitSkipsRepository() {
        var c = criteria(new BigDecimal("20"));
        var results = List.of(
                new StockScreeningResult("601398", "工商银行", "801780", "银行",
                        new BigDecimal("5.6"), new BigDecimal("0.62"), new BigDecimal("5.4"),
                        new BigDecimal("11.8"), null, null, null, null, null, null, null, null));
        when(repo.findStocks(c)).thenReturn(results);
        when(cache.get(anyString())).thenReturn(null).thenReturn(results);

        assertThat(service.screen(c)).hasSize(1);
        assertThat(service.screen(c)).hasSize(1);

        verify(repo, times(1)).findStocks(c);
        verify(cache).put(anyString(), eq(results), any(Duration.class));
    }
}
