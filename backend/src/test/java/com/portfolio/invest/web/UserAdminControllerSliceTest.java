package com.portfolio.invest.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.application.useradmin.UserAdminApplicationService;
import com.portfolio.invest.application.useradmin.UserAdminView;
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
 * 用户管理 REST 切片：端点绑定 + 安全规则（匿名 401 / 非管理员 403 / ADMIN 放行）。
 * 引入真实 SecurityConfig；@WithMockUser 的 principal 非 AuthenticatedUser，
 * ActiveUserFilter 直接放行，不触发 UserRepository 查询。
 */
@WebMvcTest(UserAdminController.class)
@Import(SecurityConfig.class)
class UserAdminControllerSliceTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private UserAdminApplicationService service;

    // SecurityConfig 装配所需依赖（切片内无真实实现）
    @MockitoBean
    private UserRepository userRepository;
    @MockitoBean
    private UserDetailsService userDetailsService;
    @MockitoBean
    private RememberMeServices rememberMeServices;
    @MockitoBean
    private PersistentTokenRepository persistentTokenRepository;

    private UserAdminView adminView() {
        return new UserAdminView(2L, "alice", "USER", "APPROVED", true);
    }

    @DisplayName("匿名访问用户列表返回401")
    @Test
    void anonymousAccessUserListReturns401() throws Exception {
        mvc.perform(get("/api/admin/users"))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("匿名操作审核端点返回401")
    @Test
    void anonymousAccessRejectEndpointReturns401() throws Exception {
        mvc.perform(post("/api/admin/users/2/reject"))
                .andExpect(status().isUnauthorized());
    }

    @DisplayName("非管理员访问返回403")
    @Test
    @WithMockUser(roles = "USER")
    void nonAdminAccessReturns403() throws Exception {
        mvc.perform(get("/api/admin/users"))
                .andExpect(status().isForbidden());
    }

    @DisplayName("非管理员操作停用端点返回403")
    @Test
    @WithMockUser(roles = "USER")
    void nonAdminDisableEndpointReturns403() throws Exception {
        mvc.perform(post("/api/admin/users/2/disable"))
                .andExpect(status().isForbidden());
    }

    @DisplayName("管理员拒绝用户返回200")
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminRejectUserReturns200() throws Exception {
        when(service.reject(2L)).thenReturn(
                new UserAdminView(2L, "alice", "USER", "REJECTED", true));

        mvc.perform(post("/api/admin/users/2/reject"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @DisplayName("管理员启用用户返回200")
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminEnableUserReturns200() throws Exception {
        when(service.enable(2L)).thenReturn(adminView());

        mvc.perform(post("/api/admin/users/2/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @DisplayName("管理员停用用户返回200")
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminDisableUserReturns200() throws Exception {
        when(service.disable(2L)).thenReturn(
                new UserAdminView(2L, "alice", "USER", "APPROVED", false));

        mvc.perform(post("/api/admin/users/2/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @DisplayName("管理员重置密码返回200")
    @Test
    @WithMockUser(roles = "ADMIN")
    void adminResetPasswordReturns200() throws Exception {
        when(service.resetPassword(2L, "NewPass1")).thenReturn(adminView());

        mvc.perform(post("/api/admin/users/2/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"NewPass1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"));
    }

    @DisplayName("重置密码为空校验失败返回400")
    @Test
    @WithMockUser(roles = "ADMIN")
    void resetPasswordEmptyValidationFailsReturns400() throws Exception {
        mvc.perform(post("/api/admin/users/2/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("新密码不能为空"));
    }
}
