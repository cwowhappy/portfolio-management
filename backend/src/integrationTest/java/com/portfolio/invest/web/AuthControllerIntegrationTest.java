package com.portfolio.invest.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import com.portfolio.invest.support.RecordingMailSender;
import com.portfolio.invest.support.TestCodes;
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
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends PostgresTestSupport {

    /** 发信桩：@Primary 覆盖未配置 SMTP 的 SmtpMailSender，发码走 RecordingMailSender 供断言/取码。 */
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
    @Autowired RecordingMailSender mailStub;

    @BeforeEach
    void clearMailStub() {
        // 单例桩跨用例累积，清空让各用例只看自己发出的邮件（断言不依赖用例执行顺序）
        mailStub.sent.clear();
    }

    @DisplayName("注册流程：发码-注册-待审核")
    @Test
    void givenIssuedCode_whenRegister_thenPendingWithVerifiedEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_alice\",\"password\":\"abc12345\",\"email\":\"mail_alice@test.local\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("验证码已发送"));
        assertThat(mailStub.sent).hasSize(1);
        assertThat(mailStub.sent.get(0).to()).isEqualTo("mail_alice@test.local");

        String code = TestCodes.extractSixDigits(mailStub.sent.get(0).text());
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_alice\",\"password\":\"abc12345\","
                                + "\"email\":\"mail_alice@test.local\",\"code\":\"" + code + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 邮箱随注册落库且标记已验证（注册流程邮箱来自持码验证的用户输入）
        var saved = userRepository.findByEmail("mail_alice@test.local").orElseThrow();
        assertThat(saved.emailVerified()).isTrue();
    }

    @DisplayName("错码注册返回400 CODE_INVALID")
    @Test
    void givenIssuedCode_whenRegisterWithWrongCode_thenCodeInvalid() throws Exception {
        mockMvc.perform(post("/api/auth/register-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_badcode\",\"password\":\"abc12345\",\"email\":\"mail_badcode@test.local\"}"))
                .andExpect(status().isOk());
        String issued = TestCodes.extractSixDigits(mailStub.sent.get(0).text());
        String wrong = issued.equals("000000") ? "111111" : "000000";

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_badcode\",\"password\":\"abc12345\","
                                + "\"email\":\"mail_badcode@test.local\",\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CODE_INVALID"));
        assertThat(userRepository.findByUsername("mail_badcode")).isEmpty();
    }

    @DisplayName("重复邮箱注册返回400 EMAIL_TAKEN")
    @Test
    void givenEmailAlreadyRegistered_whenIssueCodeForSameEmail_thenEmailTaken() throws Exception {
        register("mail_dup_a", "abc12345");

        // 第二个账号对同一邮箱发码：预检即拦截（该邮箱已随首个账号落库）
        mockMvc.perform(post("/api/auth/register-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mail_dup_b\",\"password\":\"abc12345\",\"email\":\"mail_dup_a@test.local\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("EMAIL_TAKEN"));
    }

    @DisplayName("注册后待审核不能登录")
    @Test
    void givenRegisteredPendingUser_whenLogin_thenForbidden() throws Exception {
        register("auth_alice", "abc12345");

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_alice\",\"password\":\"abc12345\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING"));
    }

    @DisplayName("审核通过后登录成功并访问me")
    @Test
    void givenApprovedUser_whenLogin_thenSucceedsAndMeAccessible() throws Exception {
        register("auth_bob", "abc12345");
        approve("auth_bob");

        var result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_bob\",\"password\":\"abc12345\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // MockMvc 不会依据 JSESSIONID cookie 重建会话，需显式传递登录请求产生的 MockHttpSession
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("auth_bob"));
    }

    @DisplayName("错误密码返回401")
    @Test
    void givenRegisteredUser_whenLoginWithWrongPassword_thenUnauthorized() throws Exception {
        register("auth_carol", "abc12345");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_carol\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @DisplayName("登录成功后轮换sessionId防会话固定")
    @Test
    void givenAnonymousPreLoginSession_whenLoginSucceeds_thenSessionIdRotated() throws Exception {
        register("auth_dave", "abc12345");
        approve("auth_dave");

        // 登录前已持有匿名会话
        MockHttpSession preLogin = new MockHttpSession();
        String oldId = preLogin.getId();

        var result = mockMvc.perform(post("/api/auth/login").session(preLogin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_dave\",\"password\":\"abc12345\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession afterLogin = (MockHttpSession) result.getRequest().getSession(false);
        org.assertj.core.api.Assertions.assertThat(afterLogin.getId()).isNotEqualTo(oldId);

        // 轮换后的会话带认证态
        mockMvc.perform(get("/api/auth/me").session(afterLogin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("auth_dave"));
    }

    @DisplayName("rememberMe仅传JSON字段也下发cookie")
    @Test
    void givenApprovedUser_whenLoginWithJsonRememberMe_thenRememberMeCookieIssued() throws Exception {
        register("auth_erin", "abc12345");
        approve("auth_erin");

        // 前端登录只发 JSON body 的 rememberMe 字段，不带 remember-me 请求参数（真实浏览器链路）；
        // 回归：loginSuccess 内部曾因缺该参数静默不下发 cookie
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_erin\",\"password\":\"abc12345\",\"rememberMe\":true}"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie()
                        .exists(com.portfolio.invest.infrastructure.security.SecurityConfig.REMEMBER_ME_COOKIE));
    }

    @DisplayName("注册登录结构性校验失败返回400")
    @Test
    void givenBlankUsernameOrMissingPassword_whenRegisterOrLogin_thenBadRequest() throws Exception {
        // 空白用户名注册 → Bean Validation 拦截（H8）；email/code 补齐让用户名成为唯一违例
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \",\"password\":\"abc12345\","
                                + "\"email\":\"blank@test.local\",\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("用户名不能为空"));

        // 缺少密码登录 → Bean Validation 拦截，不再落到认证流程
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_bob\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("密码不能为空"));
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

    private void approve(String username) {
        var user = userRepository.findByUsername(username).orElseThrow();
        userRepository.save(user.approve());
    }
}
