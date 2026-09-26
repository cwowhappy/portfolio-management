package com.portfolio.invest.application.industry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.portfolio.invest.domain.industry.FundingEvent;
import com.portfolio.invest.domain.industry.FundingEventRepository;
import com.portfolio.invest.domain.industry.IndustryRepository;
import com.portfolio.invest.domain.industry.UnlistedCompany;
import com.portfolio.invest.domain.industry.UnlistedCompanyRepository;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 导入编排服务单测（照 IndustryWatchApplicationServiceTest 纯 JUnit + Mockito 构造注入）：
 * 五层管线中的 L3（行业码白名单，一次查全文件去重集合）/ L5（文件内幂等键重复，第二行起报错）
 * / 执行（全量预检通过才逐条 upsert，累计 inserted/updated 双计数——设计规格 §九#2）。
 * 解析层 L1/L2 已由两 Parser 测试覆盖，此处样例刻意全合法（或仅触发编排层错误的行）。
 */
class IndustryCurationImportServiceTest {

    private final UnlistedCompanyRepository companyRepository = mock(UnlistedCompanyRepository.class);
    private final FundingEventRepository eventRepository = mock(FundingEventRepository.class);
    private final IndustryRepository industryRepository = mock(IndustryRepository.class);

    private final IndustryCurationImportService service =
            new IndustryCurationImportService(companyRepository, eventRepository, industryRepository);

    private static final String COMPANY_HEADER = "industry_code,company_name,segment,latest_round,"
            + "last_funding_date,total_funding_yi,summary,source_note";

    private static final String TWO_COMPANY_CSV = COMPANY_HEADER + "\n" + """
            801730,示例电池科技,动力电池,B,2026-01-15,120.50,动力电池新锐,爱企查人工核对
            801730,示例光伏科技,光伏组件,A_PLUS,2026-02-20,,组件新势力,
            """;

    private static final String EVENT_HEADER = "event_date,company_name,round,amount_yi,investors,"
            + "industry_code,segment,source_title,source_url";

    private static final String TWO_EVENT_CSV = EVENT_HEADER + "\n" + """
            2026-03-15,示例电池科技,B,8.50,深创投,801730,动力电池,睿兽分析2026-03月报,
            2026-04-15,示例光伏科技,A_PLUS,3.00,,801730,,公开报道标题,
            """;

    @DisplayName("全合法策展企业文件：全部 inserted、updated=0、行错误空")
    @Test
    void givenValidCompanyCsv_whenImportCompanies_thenAllInserted() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        when(companyRepository.upsert(any())).thenReturn(new UnlistedCompanyRepository.UpsertOutcome(true));

        var result = service.importCompanies(TWO_COMPANY_CSV);

        assertThat(result.insertedCount()).isEqualTo(2);
        assertThat(result.updatedCount()).isZero();
        assertThat(result.rowErrors()).isEmpty();
        verify(companyRepository, times(2)).upsert(any(UnlistedCompany.class));
        var captor = ArgumentCaptor.forClass(UnlistedCompany.class);
        verify(companyRepository, times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues()).extracting(UnlistedCompany::companyName)
                .containsExactly("示例电池科技", "示例光伏科技"); // 按文件序执行
    }

    @DisplayName("重导同文件：仓储全部命中幂等键，计数全落 updated")
    @Test
    void givenReimportSameFile_whenImportCompanies_thenAllUpdated() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        when(companyRepository.upsert(any())).thenReturn(new UnlistedCompanyRepository.UpsertOutcome(false));

        var result = service.importCompanies(TWO_COMPANY_CSV);

        assertThat(result.insertedCount()).isZero();
        assertThat(result.updatedCount()).isEqualTo(2);
    }

    @DisplayName("混合命中：首条插入、次条更新，双计数分别累计")
    @Test
    void givenMixedUpsertOutcomes_whenImportCompanies_thenInsertedAndUpdatedCountedSeparately() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        when(companyRepository.upsert(any(UnlistedCompany.class)))
                .thenReturn(new UnlistedCompanyRepository.UpsertOutcome(true))
                .thenReturn(new UnlistedCompanyRepository.UpsertOutcome(false));

        var result = service.importCompanies(TWO_COMPANY_CSV);

        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isEqualTo(1);
    }

    @DisplayName("L3 行业码白名单：未知码记行错误（行号+原因）、零落库、去重集合一次查库")
    @Test
    void givenUnknownIndustryCode_whenImportCompanies_thenRowErrorAndZeroWrites() {
        when(industryRepository.existsIndustry("801730")).thenReturn(false);

        var result = service.importCompanies(TWO_COMPANY_CSV);

        assertThat(result.insertedCount()).isZero();
        assertThat(result.updatedCount()).isZero();
        assertThat(result.rowErrors()).hasSize(2);
        assertThat(result.rowErrors().get(0).row()).isEqualTo(2);
        assertThat(result.rowErrors().get(0).reason()).contains("行业代码不存在").contains("801730");
        verify(companyRepository, never()).upsert(any());
        verify(industryRepository, times(1)).existsIndustry("801730"); // 两行同码只查一次
    }

    @DisplayName("L5 文件内幂等键重复：第二行起报错且全量拒绝（all-or-nothing）")
    @Test
    void givenDuplicateCompanyKeysInFile_whenImportCompanies_thenSecondOccurrenceErrorAndZeroWrites() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        String csv = COMPANY_HEADER + "\n" + """
                801730,示例电池科技,动力电池,B,2026-01-15,120.50,简介,来源
                801730,示例电池科技,动力电池改口径,B_PLUS,2026-03-01,200.00,简介改,来源改
                """;

        var result = service.importCompanies(csv);

        assertThat(result.insertedCount()).isZero();
        assertThat(result.rowErrors()).hasSize(1);
        assertThat(result.rowErrors().get(0).row()).isEqualTo(3);
        assertThat(result.rowErrors().get(0).reason()).contains("幂等键重复").contains("示例电池科技");
        verify(companyRepository, never()).upsert(any()); // 预检不通过零执行
    }

    @DisplayName("解析层错误直传：文件级表头错误原样透传，不触达仓储与白名单")
    @Test
    void givenParserLevelError_whenImportCompanies_thenErrorsPassedThrough() {
        var result = service.importCompanies("错误表头\n错误行\n");

        assertThat(result.insertedCount()).isZero();
        assertThat(result.updatedCount()).isZero();
        assertThat(result.rowErrors()).hasSize(1);
        assertThat(result.rowErrors().get(0).row()).isZero();
        verifyNoInteractions(companyRepository, industryRepository);
    }

    @DisplayName("融资事件全合法：逐条 upsert 双计数（混合命中）")
    @Test
    void givenValidEventCsv_whenImportFundingEvents_thenUpsertedWithMixedCounts() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        when(eventRepository.upsert(any(FundingEvent.class)))
                .thenReturn(new UnlistedCompanyRepository.UpsertOutcome(true))
                .thenReturn(new UnlistedCompanyRepository.UpsertOutcome(false));

        var result = service.importFundingEvents(TWO_EVENT_CSV);

        assertThat(result.insertedCount()).isEqualTo(1);
        assertThat(result.updatedCount()).isEqualTo(1);
        assertThat(result.rowErrors()).isEmpty();
        var captor = ArgumentCaptor.forClass(FundingEvent.class);
        verify(eventRepository, times(2)).upsert(captor.capture());
        assertThat(captor.getAllValues().get(0).amountYi()).isEqualByComparingTo(new BigDecimal("8.50"));
        assertThat(captor.getAllValues().get(1).investors()).isNull();
        assertThat(captor.getAllValues().get(1).segment()).isNull();
    }

    @DisplayName("融资事件 L5 幂等键（同日同企同轮）重复：第二行报错零落库")
    @Test
    void givenDuplicateEventKeysInFile_whenImportFundingEvents_thenSecondOccurrenceErrorAndZeroWrites() {
        when(industryRepository.existsIndustry("801730")).thenReturn(true);
        String csv = EVENT_HEADER + "\n" + """
                2026-03-15,示例电池科技,B,8.50,深创投,801730,动力电池,睿兽分析月报,
                2026-03-15,示例电池科技,B,9.99,高瓴,801730,动力电池,更正来源,
                """;

        var result = service.importFundingEvents(csv);

        assertThat(result.rowErrors()).hasSize(1);
        assertThat(result.rowErrors().get(0).row()).isEqualTo(3);
        verify(eventRepository, never()).upsert(any());
    }

    @DisplayName("融资事件 L3 行业码未知：行错误零落库")
    @Test
    void givenEventUnknownIndustry_whenImportFundingEvents_thenRowErrorAndZeroWrites() {
        when(industryRepository.existsIndustry("801730")).thenReturn(false);

        var result = service.importFundingEvents(TWO_EVENT_CSV);

        assertThat(result.rowErrors()).hasSize(2);
        verify(eventRepository, never()).upsert(any());
    }

    @DisplayName("策展企业文件多行业码：按去重集合逐码查白名单")
    @Test
    void givenMultipleIndustryCodes_whenImportCompanies_thenWhitelistCheckedPerDistinctCode() {
        when(industryRepository.existsIndustry(any(String.class))).thenReturn(true);
        when(companyRepository.upsert(any())).thenReturn(new UnlistedCompanyRepository.UpsertOutcome(true));
        String csv = COMPANY_HEADER + "\n" + """
                801730,示例电池科技,动力电池,B,2026-01-15,1.00,简介,来源
                801150,示例生物科技,CXO,A,2026-01-16,2.00,简介,来源
                801730,示例光伏科技,光伏组件,B,2026-01-17,3.00,简介,来源
                """;

        service.importCompanies(csv);

        verify(industryRepository, times(1)).existsIndustry("801730"); // 三行两码共两次查库
        verify(industryRepository, times(1)).existsIndustry("801150");
        verify(companyRepository, times(3)).upsert(any(UnlistedCompany.class));
    }
}
