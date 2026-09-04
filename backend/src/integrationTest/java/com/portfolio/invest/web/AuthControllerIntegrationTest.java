package com.portfolio.invest.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.support.PostgresTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerIntegrationTest extends PostgresTestSupport {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;

    @Test
    void 注册后待审核不能登录() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_alice\",\"password\":\"abc12345\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_alice\",\"password\":\"abc12345\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING"));
    }

    @Test
    void 审核通过后登录成功并访问me() throws Exception {
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

    @Test
    void 错误密码返回401() throws Exception {
        register("auth_carol", "abc12345");
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"auth_carol\",\"password\":\"wrongpass\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @Test
    void 登录成功后轮换sessionId防会话固定() throws Exception {
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

    @Test
    void rememberMe仅传JSON字段也下发cookie() throws Exception {
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

    @Test
    void 注册登录结构性校验失败返回400() throws Exception {
        // 空白用户名注册 → Bean Validation 拦截（H8）
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \",\"password\":\"abc12345\"}"))
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

    private void register(String u, String p) throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + u + "\",\"password\":\"" + p + "\"}"))
                .andExpect(status().isCreated());
    }

    private void approve(String username) {
        var user = userRepository.findByUsername(username).orElseThrow();
        userRepository.save(user.approve());
    }
}
