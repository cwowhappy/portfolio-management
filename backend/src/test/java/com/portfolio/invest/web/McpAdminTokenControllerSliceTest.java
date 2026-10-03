package com.portfolio.invest.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.mcp.McpConfigApplicationService;
import com.portfolio.invest.domain.mcp.McpErrorCode;
import com.portfolio.invest.domain.mcp.McpException;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * MCP token 管理切片（P1-10，D1）：admin-only 端点绑定 + 安全规则
 * （匿名 401 / 非管理员 403 / ADMIN 204）+ 异常映射（404 / 400 / 密钥未配置 503）。
 * 响应体无 token 回显（NFR-1）。
 */
@WebMvcTest(McpAdminTokenController.class)
@Import(SecurityConfig.class)
class McpAdminTokenControllerSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private McpConfigApplicationService service;

    // SecurityConfig 装配所需依赖（切片内无真实实现）
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private RememberMeServices rememberMeServices;
    @MockitoBean
    private PersistentTokenRepository persistentTokenRepository;

    @DisplayName("匿名设置 token 返回401")
    @Test
    void givenAnonymous_whenSetToken_thenReturn401() throws Exception {
        mvc.perform(put("/api/admin/mcp/providers/tushare/token"))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("非管理员设置 token 返回403")
    @Test
    @WithMockUser(roles = "USER")
    void givenNonAdminUser_whenSetToken_thenReturn403() throws Exception {
        mvc.perform(put("/api/admin/mcp/providers/tushare/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\"}"))
                .andExpect(status().isForbidden());
    }

    @DisplayName("管理员设置 token 成功返回204且无响应体（不回显）")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenAdminRole_whenSetToken_thenReturn204() throws Exception {
        mvc.perform(put("/api/admin/mcp/providers/tushare/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"new-token\"}"))
                .andExpect(status().isNoContent());

        verify(service).setProviderToken("tushare", "new-token");
    }

    @DisplayName("provider 不存在返回404")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenUnknownProvider_whenSetToken_thenReturn404() throws Exception {
        doThrow(new McpException(McpErrorCode.PROVIDER_NOT_FOUND, "数据源不存在"))
                .when(service).setProviderToken("nope", "t");

        mvc.perform(put("/api/admin/mcp/providers/nope/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROVIDER_NOT_FOUND"));
    }

    @DisplayName("token 为空返回400")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenBlankToken_whenSetToken_thenReturn400() throws Exception {
        mvc.perform(put("/api/admin/mcp/providers/tushare/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @DisplayName("token 超长（>512）返回400")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenTooLongToken_whenSetToken_thenReturn400() throws Exception {
        mvc.perform(put("/api/admin/mcp/providers/tushare/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + "x".repeat(513) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @DisplayName("密钥未配置返回503并提示设置 MCP_SECRET_KEY")
    @Test
    @WithMockUser(roles = "ADMIN")
    void givenKeyMissing_whenSetToken_thenReturn503() throws Exception {
        doThrow(new McpException(McpErrorCode.SECRET_KEY_MISSING, "MCP_SECRET_KEY 未配置"))
                .when(service).setProviderToken("tushare", "t");

        mvc.perform(put("/api/admin/mcp/providers/tushare/token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"t\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SECRET_KEY_MISSING"));
    }
}
