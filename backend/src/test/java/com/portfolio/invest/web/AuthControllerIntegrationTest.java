package com.portfolio.invest.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.persistence.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;

class AuthControllerIntegrationTest extends IntegrationTestBase {

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
