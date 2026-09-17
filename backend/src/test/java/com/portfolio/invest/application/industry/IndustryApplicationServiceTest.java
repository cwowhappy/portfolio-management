package com.portfolio.invest.application.industry;

import com.portfolio.invest.application.cache.ApplicationCache;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryProsperitySnapshot;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryStock;
import com.portfolio.invest.domain.industry.IndustryValuationPoint;
import com.portfolio.invest.domain.industry.IndustryValuationRow;
import com.portfolio.invest.domain.industry.Prosperity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndustryApplicationServiceTest {

    private final IndustryRepository repository = mock(IndustryRepository.class);
    private final ApplicationCache cache = mock(ApplicationCache.class);
    private final IndustryApplicationService service = new IndustryApplicationService(repository, cache);

    /** 250 点 5 年窗口：前 100 点 pe=5、后 150 点 pe=9，pb 恒 0.8。 */
    private List<IndustryValuationPoint> peWindow(String industryCode) {
        List<IndustryValuationPoint> history = new ArrayList<>();
        for (int i = 0; i < 250; i++) {
            history.add(new IndustryValuationPoint(LocalDate.of(2025, 1, 1).plusDays(i), industryCode,
                    i < 100 ? new BigDecimal("5") : new BigDecimal("9"), new BigDecimal("0.8")));
        }
        return history;
    }

    @DisplayName("board 组装 5 年窗口分位与景气标注")
    @Test
    void givenLatestHistoryAndProsperity_whenBoard_thenComposePercentileAndProsperity() {
        when(repository.findLatestIndustries()).thenReturn(List.of(
                new IndustryValuationRow("801780", "银行", new BigDecimal("5.5"), new BigDecimal("0.8"),
                        new BigDecimal("12"), new BigDecimal("4"))));
        when(repository.findValuationHistorySince(any())).thenReturn(peWindow("801780"));
        when(repository.findIndustryProsperity()).thenReturn(List.of(
                new IndustryProsperitySnapshot("801780", new BigDecimal("1"), new BigDecimal("10"), 42)));

        List<IndustryBoardView> board = service.board();

        assertThat(board).hasSize(1);
        IndustryBoardView row = board.get(0);
        // 250 点中 100 点严格小于 5.5 → 100/250 = 40.00；pb 全等于当前值 → 0.00
        assertThat(row.pePercentile()).isEqualByComparingTo("40.00");
        assertThat(row.pbPercentile()).isEqualByComparingTo("0.00");
        // ROE Δ=1 达 +1pp 门槛且营收增速=10 达 +10% 门槛（「且」双达门槛）→ UP
        assertThat(row.prosperity()).isEqualTo(Prosperity.UP);
        assertThat(row.prosperityInputs().roeDeltaMedian()).isEqualByComparingTo("1");
        assertThat(row.prosperityInputs().revenueYoyMedian()).isEqualByComparingTo("10");
        assertThat(row.prosperityInputs().sampleSize()).isEqualTo(42);
    }

    @DisplayName("景气快照存在但中位数缺失/样本为 0 → 不标注景气但仍透出输入")
    @Test
    void givenDegenerateSnapshot_whenBoard_thenProsperityNullButInputsPresent() {
        when(repository.findLatestIndustries()).thenReturn(List.of(
                new IndustryValuationRow("801780", "银行", new BigDecimal("5.5"), new BigDecimal("0.8"),
                        new BigDecimal("12"), new BigDecimal("4")),
                new IndustryValuationRow("801010", "食品饮料", new BigDecimal("25"), new BigDecimal("5"),
                        new BigDecimal("15"), new BigDecimal("1"))));
        when(repository.findValuationHistorySince(any())).thenReturn(List.of());
        // 801780：快照存在但双中位数 null 且 sampleSize=0（成分聚合无样本）；801010：无快照
        when(repository.findIndustryProsperity()).thenReturn(List.of(
                new IndustryProsperitySnapshot("801780", null, null, 0)));

        List<IndustryBoardView> board = service.board();

        assertThat(board).hasSize(2);
        IndustryBoardView degenerate = board.get(0);
        assertThat(degenerate.prosperity()).isNull();
        assertThat(degenerate.prosperityInputs()).isNotNull();
        assertThat(degenerate.prosperityInputs().sampleSize()).isEqualTo(0);
        assertThat(degenerate.pePercentile()).isNull();
        IndustryBoardView absent = board.get(1);
        assertThat(absent.prosperity()).isNull();
        assertThat(absent.prosperityInputs()).isNull();
    }

    @DisplayName("board 按日缓存：命中后不再查仓库")
    @Test
    void givenBoardCached_whenBoardTwice_thenSkipRepository() {
        when(repository.findLatestIndustries()).thenReturn(List.of(
                new IndustryValuationRow("801780", "银行", new BigDecimal("5.5"), new BigDecimal("0.8"),
                        new BigDecimal("12"), new BigDecimal("4"))));
        when(repository.findValuationHistorySince(any())).thenReturn(List.of());
        when(repository.findIndustryProsperity()).thenReturn(List.of());
        List<IndustryBoardView> cachedBoard = List.of();
        when(cache.get(anyString())).thenReturn(null).thenReturn(cachedBoard);

        assertThat(service.board()).hasSize(1);
        assertThat(service.board()).isSameAs(cachedBoard);

        verify(repository, times(1)).findLatestIndustries();
        verify(cache).put(contains("industry:board:"), any(), any(Duration.class));
    }

    @DisplayName("合法参数委托仓库并按完整参数缓存")
    @Test
    void givenValidParams_whenStocks_thenDelegateToRepository() {
        when(repository.existsIndustry("801780")).thenReturn(true);
        when(repository.findIndustryStocks("801780", "total_mv", "DESC", 1000)).thenReturn(List.of(
                new IndustryStock("601398", "工商银行", null, null, null, null, null, null, null, null)));

        assertThat(service.stocks("801780", "total_mv", "DESC", 1000)).hasSize(1);

        verify(repository).findIndustryStocks("801780", "total_mv", "DESC", 1000);
        verify(cache).put(contains("industry:801780:stocks:total_mv|DESC|1000"), any(), any(Duration.class));
    }

    @DisplayName("排序字段/方向白名单外、上限越界抛出异常")
    @Test
    void givenInvalidSortOrLimit_whenStocks_thenThrow() {
        when(repository.existsIndustry("801780")).thenReturn(true);

        IndustryException badField = catchThrowableOfType(
                () -> service.stocks("801780", "pe_ttm", "DESC", 1000), IndustryException.class);
        assertThat(badField.code()).isEqualTo(IndustryErrorCode.INVALID_SORT);

        IndustryException badDirection = catchThrowableOfType(
                () -> service.stocks("801780", "total_mv", "asc", 1000), IndustryException.class);
        assertThat(badDirection.code()).isEqualTo(IndustryErrorCode.INVALID_SORT);

        IndustryException overLimit = catchThrowableOfType(
                () -> service.stocks("801780", "total_mv", "DESC", 1001), IndustryException.class);
        assertThat(overLimit.code()).isEqualTo(IndustryErrorCode.INVALID_LIMIT);

        IndustryException zeroLimit = catchThrowableOfType(
                () -> service.stocks("801780", "total_mv", "DESC", 0), IndustryException.class);
        assertThat(zeroLimit.code()).isEqualTo(IndustryErrorCode.INVALID_LIMIT);
    }

    @DisplayName("行业不存在抛出 INDUSTRY_NOT_FOUND")
    @Test
    void givenUnknownIndustry_whenStocks_thenThrowNotFound() {
        when(repository.existsIndustry("999999")).thenReturn(false);

        IndustryException ex = catchThrowableOfType(
                () -> service.stocks("999999", "total_mv", "DESC", 1000), IndustryException.class);

        assertThat(ex.code()).isEqualTo(IndustryErrorCode.INDUSTRY_NOT_FOUND);
    }
}
