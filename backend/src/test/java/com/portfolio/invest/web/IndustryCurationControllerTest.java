package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.industry.CurationImportResult;
import com.portfolio.invest.application.industry.IndustryCurationApplicationService;
import com.portfolio.invest.application.industry.IndustryCurationImportService;
import com.portfolio.invest.application.industry.SaveUnlistedCompanyCommand;
import com.portfolio.invest.domain.industry.FundingRound;
import com.portfolio.invest.domain.industry.IndustryErrorCode;
import com.portfolio.invest.domain.industry.IndustryException;
import com.portfolio.invest.domain.industry.UnlistedCompany;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 策展写侧切片（照 PortfolioImportControllerTest 基座）：JSON CRUD（UnlistedCompanyView
 * 双轮次字段）、模板下载（BOM/attachment/与解析器样例逐行同源）、multipart 导入
 * （CurationImportResult 双计数 JSON、文件级 400 INVALID_CSV、匿名 401）。
 * 业务服务打桩；安全用 Boot 默认链（/api/industry-curation 非公开前缀 → 需认证 + CSRF）。
 */
@WebMvcTest(IndustryCurationController.class)
class IndustryCurationControllerTest {

    /** 与两 Parser 测试样例（及模板常量去 BOM 后）逐行同源。 */
    private static final String COMPANY_CSV = """
            industry_code,company_name,segment,latest_round,last_funding_date,total_funding_yi,summary,source_note
            801730,示例电池科技,动力电池,B,2026-08-15,120.50,动力电池新锐,爱企查人工核对
            """;

    private static final String EVENT_CSV = """
            event_date,company_name,round,amount_yi,investors,industry_code,segment,source_title,source_url
            2026-08-15,某生物科技公司,B_PLUS,3.20,高瓴·红杉,801150,CXO,睿兽分析2026-08月报,
            """;

    private static final String SAVE_BODY = """
            {"industryCode":"801730","companyName":"示例电池科技","segment":"动力电池",
            "latestRound":"B","lastFundingDate":"2026-08-15","totalFundingYi":120.50,
            "summary":"动力电池新锐","sourceNote":"爱企查人工核对"}
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private IndustryCurationApplicationService curationService;

    @MockitoBean
    private IndustryCurationImportService importService;

    /** 路由级鉴权已由独立前缀达成，控制器方法不收 auth 参数；切片仍需已认证主体过安全链。 */
    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static UnlistedCompany company(Long id) {
        return new UnlistedCompany(id, "801730", "示例电池科技", "动力电池", FundingRound.B,
                LocalDate.of(2026, 8, 15), new BigDecimal("120.50"), "动力电池新锐",
                "爱企查人工核对", Instant.parse("2026-09-26T00:00:00Z"));
    }

    private static MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "import.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("POST /companies：200 返回 UnlistedCompanyView（轮次枚举名+label 双字段）")
    void givenValidCommand_whenPostCompanies_thenViewWithDualRoundFields() throws Exception {
        when(curationService.save(eq(null), any(SaveUnlistedCompanyCommand.class))).thenReturn(company(42L));

        mvc.perform(post("/api/industry-curation/companies").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.industryCode").value("801730"))
                .andExpect(jsonPath("$.companyName").value("示例电池科技"))
                .andExpect(jsonPath("$.latestRound").value("B"))
                .andExpect(jsonPath("$.latestRoundLabel").value("B轮"))
                .andExpect(jsonPath("$.lastFundingDate").value("2026-08-15"))
                .andExpect(jsonPath("$.totalFundingYi").value(120.50));
    }

    @Test
    @DisplayName("PUT /companies/{id}：以路径 id 更新返回 200")
    void givenValidCommand_whenPutCompanies_thenReturnUpdatedView() throws Exception {
        when(curationService.save(eq(5L), any(SaveUnlistedCompanyCommand.class))).thenReturn(company(5L));

        mvc.perform(put("/api/industry-curation/companies/5").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(5));
    }

    @Test
    @DisplayName("命令缺必填字段：400 INVALID_REQUEST（Bean Validation）")
    void givenBlankRequiredFields_whenPostCompanies_then400InvalidRequest() throws Exception {
        mvc.perform(post("/api/industry-curation/companies").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"industryCode\":\"\",\"companyName\":\"x\",\"latestRound\":\"B\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(curationService);
    }

    @Test
    @DisplayName("同行业同名企业冲突：409 INDUSTRY_UNLISTED_DUPLICATE（照 wiki DUPLICATE_METRIC→CONFLICT 先例）")
    void givenDuplicateCompany_whenPostCompanies_then409Conflict() throws Exception {
        when(curationService.save(eq(null), any(SaveUnlistedCompanyCommand.class))).thenThrow(
                new IndustryException(IndustryErrorCode.UNLISTED_DUPLICATE, "该行业已存在同名策展企业"));

        mvc.perform(post("/api/industry-curation/companies").with(authentication(auth())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INDUSTRY_UNLISTED_DUPLICATE"))
                .andExpect(jsonPath("$.message").value("该行业已存在同名策展企业"));
    }

    @Test
    @DisplayName("DELETE /companies/{id} 与 /funding-events/{id}：均 204 且委托服务")
    void givenIds_whenDelete_thenReturn204() throws Exception {
        mvc.perform(delete("/api/industry-curation/companies/5")
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isNoContent());
        verify(curationService).deleteCompany(5L);

        mvc.perform(delete("/api/industry-curation/funding-events/3")
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isNoContent());
        verify(curationService).deleteFundingEvent(3L);
    }

    @Test
    @DisplayName("策展企业模板下载：BOM 头+八列表头+示例行（attachment）且与解析器样例逐行同源")
    void givenTemplateRequest_whenGetCompaniesTemplate_thenCsvWithBom() throws Exception {
        MvcResult r = mvc.perform(get("/api/industry-curation/companies/import/template")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=unlisted-companies-template.csv"))
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                .andReturn();

        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("﻿" + COMPANY_CSV); // 与解析器测试样例逐行同源（BOM 之外零漂移）
    }

    @Test
    @DisplayName("融资事件模板下载：BOM 头+九列表头+示例行（attachment）且与解析器样例逐行同源")
    void givenTemplateRequest_whenGetFundingEventsTemplate_thenCsvWithBom() throws Exception {
        MvcResult r = mvc.perform(get("/api/industry-curation/funding-events/import/template")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=industry-funding-events-template.csv"))
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                .andReturn();

        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo("﻿" + EVENT_CSV);
    }

    @Test
    @DisplayName("策展企业 multipart 导入：200 返回 CurationImportResult 双计数 JSON（成功与行错误两形态）")
    void givenMultipartFile_whenPostCompaniesImport_thenResultJson() throws Exception {
        when(importService.importCompanies(COMPANY_CSV))
                .thenReturn(new CurationImportResult(1, 0, List.of()));

        mvc.perform(multipart("/api/industry-curation/companies/import").file(csvFile(COMPANY_CSV))
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insertedCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(0))
                .andExpect(jsonPath("$.rowErrors").isEmpty());

        // 行级错误形态：双计数归零 + rowErrors（row/reason），仍 200（双层错误语义）
        when(importService.importCompanies(COMPANY_CSV)).thenReturn(new CurationImportResult(0, 0,
                List.of(new CurationImportResult.RowError(2, "行业代码不存在: 999999"))));

        mvc.perform(multipart("/api/industry-curation/companies/import").file(csvFile(COMPANY_CSV))
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insertedCount").value(0))
                .andExpect(jsonPath("$.rowErrors[0].row").value(2))
                .andExpect(jsonPath("$.rowErrors[0].reason").value("行业代码不存在: 999999"));
    }

    @Test
    @DisplayName("融资事件 multipart 导入：200 返回双计数 JSON")
    void givenMultipartFile_whenPostFundingEventsImport_thenResultJson() throws Exception {
        when(importService.importFundingEvents(EVENT_CSV))
                .thenReturn(new CurationImportResult(1, 1, List.of()));

        mvc.perform(multipart("/api/industry-curation/funding-events/import").file(csvFile(EVENT_CSV))
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.insertedCount").value(1))
                .andExpect(jsonPath("$.updatedCount").value(1));
    }

    @Test
    @DisplayName("空文件返回 400 INVALID_CSV 且不触达服务")
    void givenEmptyFile_whenPostImport_then400InvalidCsv() throws Exception {
        mvc.perform(multipart("/api/industry-curation/companies/import")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv", new byte[0]))
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INDUSTRY_INVALID_CSV"))
                .andExpect(jsonPath("$.message").value("文件为空"));

        verifyNoInteractions(importService);
    }

    @Test
    @DisplayName("超 1MB 文件返回 400 INVALID_CSV 且不触达服务")
    void givenOversizedFile_whenPostImport_then400InvalidCsv() throws Exception {
        mvc.perform(multipart("/api/industry-curation/funding-events/import")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv",
                                new byte[1024 * 1024 + 1]))
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INDUSTRY_INVALID_CSV"))
                .andExpect(jsonPath("$.message").value("文件过大（上限 1MB）"));

        verifyNoInteractions(importService);
    }

    @Test
    @DisplayName("未登录访问写侧端点均 401（独立前缀非公开段，回归确认）")
    void givenAnonymous_whenAccessCurationEndpoints_then401() throws Exception {
        mvc.perform(post("/api/industry-curation/companies").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(SAVE_BODY))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/industry-curation/companies/import/template"))
                .andExpect(status().isUnauthorized());

        mvc.perform(multipart("/api/industry-curation/companies/import")
                        .file(csvFile(COMPANY_CSV)).with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
