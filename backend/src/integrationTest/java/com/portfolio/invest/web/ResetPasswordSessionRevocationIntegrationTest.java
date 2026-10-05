package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.User;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.domain.user.UserRole;
import com.portfolio.invest.domain.user.UserStatus;
import com.portfolio.invest.support.PostgresTestSupport;
import com.portfolio.invest.support.RecordingMailSender;
import com.portfolio.invest.support.TestCodes;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * B14：重置密码（自助/管理员代重置）吊销该用户全部 HTTP 会话。
 *
 * <p>验收口径：重置成功后，该用户既有 JSESSIONID 再请求受保护端点必须 401（B14 前：仍 200）；
 * 其他用户会话不受影响；新密码可正常登录获得新会话。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ResetPasswordSessionRevocationIntegrationTest extends PostgresTestSupport {

    /** 发信桩：@Primary 覆盖未配置 SMTP 的 SmtpMailSender，自助重置发码走桩取码。 */
    @TestConfiguration
    static class MailStub {
        @Bean
        @Primary
        RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired RecordingMailSender mailStub;

    @BeforeEach
    void clearMailStub() {
        mailStub.sent.clear();
    }

    @DisplayName("自助重置密码：该用户全部会话401，他人会话不受影响，新密码可登录")
    @Test
    void givenTwoSessions_whenSelfResetPassword_thenAllSessionsRevokedAndNewLoginWorks() throws Exception {
        register("sessrev_alice", "abc12345");
        approveDirect("sessrev_alice");
        register("sessrev_carol", "abc12345");
        approveDirect("sessrev_carol");

        MockHttpSession aliceSession1 = login("sessrev_alice", "abc12345");
        MockHttpSession aliceSession2 = login("sessrev_alice", "abc12345");
        MockHttpSession carolSession = login("sessrev_carol", "abc12345");

        // 前置：重置前三个会话均可用
        mockMvc.perform(get("/api/auth/me").session(aliceSession1)).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").session(aliceSession2)).andExpect(status().isOk());

        // 另一客户端（无会话）自助重置 sessrev_alice 的密码
        mockMvc.perform(post("/api/auth/reset-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"sessrev_alice\"}"))
                .andExpect(status().isOk());
        String code = TestCodes.extractSixDigits(mailStub.sent.get(mailStub.sent.size() - 1).text());
        mockMvc.perform(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"sessrev_alice\",\"code\":\"" + code
                                + "\",\"newPassword\":\"newpass77\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("密码已重置"));

        // 旧密码立即失效：旧密码登录 401
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sessrev_alice\",\"password\":\"abc12345\"}"))
                .andExpect(status().isUnauthorized());

        // alice 的两个既有会话再请求受保护端点 → 401（B14 修复前为 200）
        mockMvc.perform(get("/api/auth/me").session(aliceSession1))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));
        mockMvc.perform(get("/api/auth/me").session(aliceSession2))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        // 他人会话不受影响
        mockMvc.perform(get("/api/auth/me").session(carolSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("sessrev_carol"));

        // 新密码登录得到可用新会话
        MockHttpSession newSession = login("sessrev_alice", "newpass77");
        mockMvc.perform(get("/api/auth/me").session(newSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("sessrev_alice"));
    }

    @DisplayName("管理员重置密码：目标用户旧会话401，管理员自身会话不受影响")
    @Test
    void givenAdminResetsUserPassword_whenUserHasActiveSession_thenUserSessionRevokedAdminUnaffected() throws Exception {
        register("sessrev_bob", "abc12345");
        approveDirect("sessrev_bob");
        seedAdmin("sessrev_admin", "admin12345");

        MockHttpSession bobSession = login("sessrev_bob", "abc12345");
        MockHttpSession adminSession = login("sessrev_admin", "admin12345");
        Long bobId = userRepository.findByUsername("sessrev_bob").orElseThrow().id();

        mockMvc.perform(post("/api/admin/users/{id}/reset-password", bobId).session(adminSession)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"newpass88\"}"))
                .andExpect(status().isOk());

        // bob 旧会话再请求受保护端点 → 401（B14 修复前为 200）
        mockMvc.perform(get("/api/auth/me").session(bobSession))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_EXPIRED"));

        // 管理员自身会话不受影响；bob 可用新密码登录
        mockMvc.perform(get("/api/auth/me").session(adminSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("sessrev_admin"));
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sessrev_bob\",\"password\":\"newpass88\"}"))
                .andExpect(status().isOk());
    }

    /** 三段式注册：发码（邮件桩取码）→ 携码注册；邮箱由用户名派生保证类内唯一。 */
    private void register(String username, String password) throws Exception {
        String email = username + "@test.local";
        mockMvc.perform(post("/api/auth/register-code").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isOk());
        String code = TestCodes.extractSixDigits(mailStub.sent.get(mailStub.sent.size() - 1).text());
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"email\":\"" + email + "\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isCreated());
    }

    private void approveDirect(String username) {
        var user = userRepository.findByUsername(username).orElseThrow();
        userRepository.save(user.approve());
    }

    /** 直存 APPROVED 管理员（参照 UserAdminControllerIntegrationTest.seedAdmin）。 */
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
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session).as("登录成功必须建立会话").isNotNull();
        return session;
    }
}
