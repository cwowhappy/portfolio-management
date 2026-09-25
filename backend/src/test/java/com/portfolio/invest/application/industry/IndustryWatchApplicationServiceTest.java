package com.portfolio.invest.application.industry;

import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.IndustryWatchItem;
import com.portfolio.invest.domain.industry.IndustryWatchRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndustryWatchApplicationServiceTest {

    private final IndustryWatchRepository watchRepository = mock(IndustryWatchRepository.class);
    private final IndustryRepository industryRepository = mock(IndustryRepository.class);
    private final IndustryWatchApplicationService service =
            new IndustryWatchApplicationService(watchRepository, industryRepository);

    @DisplayName("关注未知行业码：抛 INDUSTRY_NOT_FOUND 且不落库")
    @Test
    void givenUnknownIndustryCode_whenWatch_thenIndustryNotFound() {
        when(watchRepository.existsByUserIdAndIndustryCode(1L, "999999")).thenReturn(false);
        when(industryRepository.existsIndustry("999999")).thenReturn(false);

        assertThatThrownBy(() -> service.watch(1L, "999999"))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> assertThat(e.code()).isEqualTo(IndustryErrorCode.INDUSTRY_NOT_FOUND));

        verify(watchRepository, never()).save(any());
    }

    @DisplayName("已关注行业：幂等短路不再校验不落库")
    @Test
    void givenAlreadyWatched_whenWatch_thenShortCircuitWithoutSave() {
        when(watchRepository.existsByUserIdAndIndustryCode(1L, "801780")).thenReturn(true);

        service.watch(1L, "801780");

        verify(watchRepository, never()).save(any());
    }

    @DisplayName("正常关注：trim 后以当前时间落库（id 由库生成）")
    @Test
    void givenValidIndustryWithSpaces_whenWatch_thenSaveTrimmedCodeWithNow() {
        when(watchRepository.existsByUserIdAndIndustryCode(1L, "801780")).thenReturn(false);
        when(industryRepository.existsIndustry("801780")).thenReturn(true);

        service.watch(1L, " 801780 ");

        var captor = ArgumentCaptor.forClass(IndustryWatchItem.class);
        verify(watchRepository).save(captor.capture());
        IndustryWatchItem saved = captor.getValue();
        assertThat(saved.id()).isNull();
        assertThat(saved.userId()).isEqualTo(1L);
        assertThat(saved.industryCode()).isEqualTo("801780");
        assertThat(saved.addedAt()).isNotNull();
    }

    @DisplayName("取关：委托仓储删除（仓储自身幂等）")
    @Test
    void whenUnwatch_thenDelegateDelete() {
        service.unwatch(1L, "801780");
        verify(watchRepository).deleteByUserIdAndIndustryCode(1L, "801780");
    }

    @DisplayName("列表：映射为 IndustryWatchView（industryCode + addedAt，排序由仓储保证）")
    @Test
    void givenWatchedItems_whenListWatched_thenMappedViews() {
        Instant later = Instant.parse("2026-09-21T00:00:00Z");
        Instant earlier = Instant.parse("2026-09-20T00:00:00Z");
        when(watchRepository.findByUserId(1L)).thenReturn(List.of(
                new IndustryWatchItem(1L, 1L, "801780", later),
                new IndustryWatchItem(2L, 1L, "801010", earlier)));

        var views = service.listWatched(1L);

        assertThat(views).containsExactly(
                new IndustryWatchView("801780", later),
                new IndustryWatchView("801010", earlier));
    }
}
