package com.portfolio.invest.application.industry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.industry.FundingEventRepository;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.UnlistedCompany;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 策展单条 CRUD 服务单测（Mockito 构造注入，照 IndustryWatchApplicationServiceTest 先例）：
 * save 双态（id null 插入 / 更新前 findById 不存在抛 UNLISTED_NOT_FOUND）、行业白名单与
 * 轮次校验（错误码断言）、幂等删除委托。
 */
class IndustryCurationApplicationServiceTest {

    private final UnlistedCompanyRepository companyRepository = mock(UnlistedCompanyRepository.class);
    private final FundingEventRepository eventRepository = mock(FundingEventRepository.class);
    private final IndustryRepository industryRepository = mock(IndustryRepository.class);

    private final IndustryCurationApplicationService service =
            new IndustryCurationApplicationService(companyRepository, eventRepository, industryRepository);

    private static final SaveUnlistedCompanyCommand CMD = new SaveUnlistedCompanyCommand(
            "801730", "示例电池科技", "动力电池", "B",
            LocalDate.of(2026, 8, 15), new BigDecimal("120.50"), "动力电池新锐", "爱企查人工核对");

    @DisplayName("新增（id=null）：白名单/轮次校验通过后落库并回带库生成 id")
    @Test
    void givenNewCompanyWithoutId_whenSave_thenSavedWithGeneratedId() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        UnlistedCompany saved = new UnlistedCompany(42L, "801730", "示例电池科技", "动力电池",
                com.portfolio.invest.domain.industry.FundingRound.B, LocalDate.of(2026, 8, 15),
                new BigDecimal("120.50"), "动力电池新锐", "爱企查人工核对", Instant.now());
        when(companyRepository.save(any(UnlistedCompany.class))).thenReturn(saved);

        var result = service.save(null, CMD);

        assertThat(result.id()).isEqualTo(42L);
        var captor = ArgumentCaptor.forClass(UnlistedCompany.class);
        verify(companyRepository).save(captor.capture());
        assertThat(captor.getValue().id()).isNull(); // 插入路径 id 由库生成
        assertThat(captor.getValue().latestRound()).isEqualTo(com.portfolio.invest.domain.industry.FundingRound.B);
    }

    @DisplayName("更新（id 非空）：findById 命中后以同 id 落库")
    @Test
    void givenExistingId_whenSave_thenUpdatedWithSameId() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        when(companyRepository.findById(5L)).thenReturn(java.util.Optional.of(new UnlistedCompany(5L,
                "801730", "旧名", null, com.portfolio.invest.domain.industry.FundingRound.A, null,
                null, null, null, Instant.now())));
        when(companyRepository.save(any(UnlistedCompany.class))).thenAnswer(inv -> inv.getArgument(0));

        var result = service.save(5L, CMD);

        assertThat(result.id()).isEqualTo(5L);
        var captor = ArgumentCaptor.forClass(UnlistedCompany.class);
        verify(companyRepository).save(captor.capture());
        assertThat(captor.getValue().id()).isEqualTo(5L);
        assertThat(captor.getValue().companyName()).isEqualTo("示例电池科技");
    }

    @DisplayName("更新不存在的 id：抛 UNLISTED_NOT_FOUND 且不落库")
    @Test
    void givenMissingIdOnUpdate_whenSave_thenUnlistedNotFound() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        when(companyRepository.findById(5L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.save(5L, CMD))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> assertThat(e.code()).isEqualTo(IndustryErrorCode.UNLISTED_NOT_FOUND));

        verify(companyRepository, never()).save(any());
    }

    @DisplayName("行业码不在白名单：抛 INDUSTRY_NOT_FOUND（文案照 stocks 口径）且不触仓储")
    @Test
    void givenUnknownIndustry_whenSave_thenIndustryNotFound() {
        when(industryRepository.existsIndustry("999999")).thenReturn(false);
        var cmd = new SaveUnlistedCompanyCommand("999999", "示例公司", null, "B", null, null, null, null);

        assertThatThrownBy(() -> service.save(null, cmd))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(IndustryErrorCode.INDUSTRY_NOT_FOUND);
                            assertThat(e.getMessage()).contains("行业不存在").contains("999999");
                        });

        verifyNoSaveInteractions();
    }

    @DisplayName("轮次非法：抛 INVALID_ROUND 且不落库")
    @Test
    void givenInvalidRound_whenSave_thenInvalidRound() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        var cmd = new SaveUnlistedCompanyCommand("801730", "示例公司", null, "X轮", null, null, null, null);

        assertThatThrownBy(() -> service.save(null, cmd))
                .isInstanceOfSatisfying(IndustryException.class,
                        e -> {
                            assertThat(e.code()).isEqualTo(IndustryErrorCode.INVALID_ROUND);
                            assertThat(e.getMessage()).contains("X轮");
                        });

        verifyNoSaveInteractions();
    }

    @DisplayName("删除策展企业：委托仓储（仓储自身幂等）")
    @Test
    void whenDeleteCompany_thenDelegateToRepository() {
        service.deleteCompany(7L);
        verify(companyRepository).deleteById(7L);
    }

    @DisplayName("删除融资事件：委托仓储（仓储自身幂等）")
    @Test
    void whenDeleteFundingEvent_thenDelegateToRepository() {
        service.deleteFundingEvent(9L);
        verify(eventRepository).deleteById(9L);
    }

    private void verifyNoSaveInteractions() {
        verify(companyRepository, never()).save(any());
    }
}
