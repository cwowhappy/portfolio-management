package com.portfolio.invest.bdd.steps;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.bdd.CucumberSpringConfig;
import com.portfolio.invest.domain.user.UserRepository;
import com.portfolio.invest.infrastructure.security.SecurityConfig;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 管理员重置密码吊销 remember-me 步骤：真实上下文 + 真实 PG，remember-me 走真实
 * DaoAuthenticationProvider 自动认证。MockMvc 不会自动管理 cookie，登录响应的
 * remember-me cookie 显式提取后在后续请求用 {@code .cookie(...)} 携带（不带 session，
 * 模拟关闭浏览器后仅凭 remember-me cookie 自动登录）。
 */
public class AdminResetPasswordSteps {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ScenarioContext ctx;

    @当("该用户勾选记住我登录成功")
    public void 勾选记住我登录成功() throws Exception {
        // 与前端真实链路一致：只发 JSON body 的 rememberMe 字段，不带 remember-me 请求参数
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ctx.getUsername() + "\",\"password\":\"" + ctx.getPassword()
                                + "\",\"rememberMe\":true}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists(SecurityConfig.REMEMBER_ME_COOKIE))
                .andReturn();
        ctx.setRememberMeCookie(result.getResponse().getCookie(SecurityConfig.REMEMBER_ME_COOKIE));
    }

    @那么("关闭浏览器后凭记住我Cookie可以自动登录")
    public void 凭记住我Cookie自动登录成功() throws Exception {
        // 不带 session 仅带 remember-me cookie：模拟关闭浏览器后的自动登录
        mockMvc.perform(get("/api/conversations").cookie(ctx.getRememberMeCookie()))
                .andExpect(status().isOk());
    }

    @当("管理员将该用户密码重置为 {string}")
    public void 管理员重置密码(String newPassword) throws Exception {
        Long userId = userRepository.findByUsername(ctx.getUsername()).orElseThrow().id();
        ctx.setUserId(userId);
        mockMvc.perform(post("/api/admin/users/{id}/reset-password", userId)
                        .session(adminSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newPassword\":\"" + newPassword + "\"}"))
                .andExpect(status().isOk());
    }

    @那么("旧的记住我Cookie自动登录应返回未授权")
    public void 旧记住我Cookie自动登录未授权() throws Exception {
        // 重置密码已吊销持久令牌，旧 cookie 自动登录失败，匿名访问受保护端点 → 401
        mockMvc.perform(get("/api/conversations").cookie(ctx.getRememberMeCookie()))
                .andExpect(status().isUnauthorized());
    }

    @那么("该用户使用旧密码登录应返回未授权")
    public void 旧密码登录未授权() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ctx.getUsername() + "\",\"password\":\"" + ctx.getPassword()
                                + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    @那么("该用户使用新密码 {string} 登录成功")
    public void 新密码登录成功(String newPassword) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + ctx.getUsername() + "\",\"password\":\"" + newPassword
                                + "\"}"))
                .andExpect(status().isOk());
    }

    /** 内置管理员（AdminSeedRunner 种子）登录后台，会话在场景内复用。 */
    private MockHttpSession adminSession() throws Exception {
        if (ctx.getAdminSession() == null) {
            MvcResult result = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + CucumberSpringConfig.ADMIN_USERNAME
                                    + "\",\"password\":\"" + CucumberSpringConfig.ADMIN_PASSWORD + "\"}"))
                    .andExpect(status().isOk())
                    .andReturn();
            ctx.setAdminSession((MockHttpSession) result.getRequest().getSession(false));
        }
        return ctx.getAdminSession();
    }
}
