package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.portfolio.ImportResult;
import com.portfolio.invest.application.portfolio.PortfolioApplicationService;
import com.portfolio.invest.application.portfolio.PortfolioImportService;
import com.portfolio.invest.domain.portfolio.ImportSimulator;
import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.security.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * CSV 导入两端点切片：模板下载（BOM/attachment/与解析器样例逐行同源）与 multipart 导入
 * （ImportResult JSON 两形态、文件级 400、匿名 401）。业务编排打桩；安全用 Boot 默认链
 * （需认证 + CSRF 开启），与 {@link PortfolioControllerSliceTest} 同构。
 */
@WebMvcTest(PortfolioController.class)
class PortfolioImportControllerTest {

    /** 与 CsvImportParserTest.SIX_TYPE_CSV（及 PortfolioImportTemplate.CSV 去 BOM 后）逐行同源。 */
    private static final String SIX_TYPE_CSV = """
            日期,类型,证券代码,证券名称,分组名称,价格,数量,费用/金额,备注
            2024-01-05,BUY,600519,贵州茅台,主账户,1680.00,100,5.00,首次建仓
            2024-06-20,SELL,600519,贵州茅台,主账户,1750.50,50,5.00,
            2024-07-01,CASH_DIVIDEND,600519,贵州茅台,主账户,25.63,,,
            2024-07-01,STOCK_DIVIDEND,600519,贵州茅台,主账户,0.05,,,
            2024-01-03,DEPOSIT,,,主账户,,,200000.00,初始入金
            2024-08-10,WITHDRAW,,,主账户,,,10000.00,出金买房
            """;

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PortfolioApplicationService service;

    @MockitoBean
    private PortfolioImportService importService;

    /** 构造已认证主体：控制器 currentUserId(auth) 会 cast auth.getPrincipal() 为 AuthenticatedUser。 */
    private Authentication auth() {
        var user = User.reconstitute(1L, "u", "p", UserRole.USER, UserStatus.APPROVED, true,
                Instant.now(), Instant.now());
        var principal = new AuthenticatedUser(user);
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private static MockMultipartFile csvFile(String content) {
        return new MockMultipartFile("file", "import.csv", "text/csv",
                content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("模板下载返回 BOM 头+九列表头+六类型示例行（attachment）")
    void givenTemplateRequest_whenGetImportTemplate_thenCsvWithBom() throws Exception {
        MvcResult r = mvc.perform(get("/api/portfolio/import/template").with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=import-template.csv"))
                .andExpect(header().string("Content-Type", "text/csv; charset=UTF-8"))
                .andReturn();

        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).startsWith("\uFEFF日期,类型,证券代码,证券名称,分组名称,价格,数量,费用/金额,备注");
        // 模板正文与解析器测试六类型样例逐行同源（BOM 之外不允许任何漂移）
        assertThat(body).isEqualTo("\uFEFF" + SIX_TYPE_CSV);
    }

    @Test
    @DisplayName("导入端点 multipart 返回 ImportResult JSON（全成功与全拒绝两形态）")
    void givenMultipartFile_whenPostImport_thenImportResultJson() throws Exception {
        when(importService.importCsv(1L, SIX_TYPE_CSV)).thenReturn(new ImportResult(6, List.of()));

        mvc.perform(multipart("/api/portfolio/import").file(csvFile(SIX_TYPE_CSV))
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(6))
                .andExpect(jsonPath("$.rowErrors").isEmpty());

        // all-or-nothing 拒绝形态：importedCount=0 + rowErrors 非空（row/reason 为 Task 6 前端契约键）
        when(importService.importCsv(1L, SIX_TYPE_CSV)).thenReturn(new ImportResult(0,
                List.of(new ImportSimulator.RowError(3, "分组不存在「主账户」，请先在页面创建或修改 CSV"))));

        mvc.perform(multipart("/api/portfolio/import").file(csvFile(SIX_TYPE_CSV))
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.importedCount").value(0))
                .andExpect(jsonPath("$.rowErrors[0].row").value(3))
                .andExpect(jsonPath("$.rowErrors[0].reason").value("分组不存在「主账户」，请先在页面创建或修改 CSV"));

        // 控制器把 multipart 字节按 UTF-8 解码成完整文本透传服务（stub 精确匹配 + 显式次数断言）
        verify(importService, times(2)).importCsv(1L, SIX_TYPE_CSV);
    }

    @Test
    @DisplayName("空文件返回 400 INVALID_INPUT 且不触达服务")
    void givenEmptyFile_whenPostImport_then400InvalidInput() throws Exception {
        mvc.perform(multipart("/api/portfolio/import")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv", new byte[0]))
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("文件为空"));

        verifyNoInteractions(importService);
    }

    @Test
    @DisplayName("超 1MB 文件返回 400 INVALID_INPUT 且不触达服务")
    void givenOversizedFile_whenPostImport_then400InvalidInput() throws Exception {
        mvc.perform(multipart("/api/portfolio/import")
                        .file(new MockMultipartFile("file", "import.csv", "text/csv",
                                new byte[1024 * 1024 + 1]))
                        .with(authentication(auth())).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.message").value("文件过大（上限 1MB）"));

        verifyNoInteractions(importService);
    }

    @Test
    @DisplayName("未登录访问两端点均 401（/api/portfolio/** 本就需登录，回归确认）")
    void givenAnonymous_whenAccessImportEndpoints_then401() throws Exception {
        mvc.perform(multipart("/api/portfolio/import").file(csvFile(SIX_TYPE_CSV)).with(csrf()))
                .andExpect(status().isUnauthorized());

        mvc.perform(get("/api/portfolio/import/template"))
                .andExpect(status().isUnauthorized());
    }
}
