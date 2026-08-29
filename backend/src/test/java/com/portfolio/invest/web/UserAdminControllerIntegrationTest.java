package com.portfolio.invest.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.infrastructure.persistence.IntegrationTestBase;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

class UserAdminControllerIntegrationTest extends IntegrationTestBase {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired org.springframework.security.web.authentication.rememberme.PersistentTokenRepository tokenRepository;

    @Test
    void 普通用户访问admin接口返回403() throws Exception {
        register("adminit_user", "abc12345");
        approveDirect("adminit_user");

        MockHttpSession session = login("adminit_user", "abc12345");
        mockMvc.perform(get("/api/admin/users").session(session))
                .andExpect(status().isForbidden());
    }

    @Test
    void 管理员审核通过后用户可登录() throws Exception {
        register("adminit_pending", "abc12345");
        seedAdmin("adminit_admin", "admin12345");

        MockHttpSession adminSession = login("adminit_admin", "admin12345");
        Long pendingId = userRepository.findByUsername("adminit_pending").orElseThrow().id();

        mockMvc.perform(post("/api/admin/users/{id}/approve", pendingId).session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"adminit_pending\",\"password\":\"abc12345\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 管理员重置密码后该用户rememberMe令牌被吊销() throws Exception {
        register("adminit_reset", "abc12345");
        approveDirect("adminit_reset");
        seedAdmin("adminit_admin2", "admin12345");
        MockHttpSession adminSession = login("adminit_admin2", "admin12345");
        Long userId = userRepository.findByUsername("adminit_reset").orElseThrow().id();

        // 该用户有一个有效的 remember-me 令牌
        tokenRepository.createNewToken(new org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken(
                "adminit_reset", "series-1", "token-1", java.util.Date.from(Instant.now())));
        org.assertj.core.api.Assertions.assertThat(tokenRepository.getTokenForSeries("series-1")).isNotNull();

        mockMvc.perform(post("/api/admin/users/{id}/reset-password", userId).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isOk());

        org.assertj.core.api.Assertions.assertThat(tokenRepository.getTokenForSeries("series-1")).isNull();
    }

    private void register(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());
    }

    private void approveDirect(String username) {
        var user = userRepository.findByUsername(username).orElseThrow();
        userRepository.save(user.approve());
    }

    private void seedAdmin(String username, String password) {
        userRepository.save(User.reconstitute(null, username, passwordEncoder.encode(password),
                UserRole.ADMIN, UserStatus.APPROVED, true, Instant.now(), Instant.now()));
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
