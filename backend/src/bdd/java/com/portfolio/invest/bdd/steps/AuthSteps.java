package com.portfolio.invest.bdd.steps;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.bdd.CucumberSpringConfig;
import com.portfolio.invest.domain.user.UserRepository;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** 注册/登录/审核/停用启用步骤：旅程类场景走真实 HTTP（MockMvc + session）全链路。 */
public class AuthSteps {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ScenarioContext ctx;

    @假如("用户 {string} 以密码 {string} 注册")
    public void 用户注册(String username, String password) throws Exception {
        ctx.setUsername(username);
        ctx.setPassword(password);
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isCreated());
    }

    @那么("该用户登录应被拒绝并提示待审核")
    public void 登录被拒待审核() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_PENDING"));
    }

    @当("管理员审核通过该用户")
    public void 管理员审核通过() throws Exception {
        mockMvc.perform(post("/api/admin/users/{id}/approve", currentUserId())
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @那么("该用户可以成功登录")
    public void 用户登录成功() throws Exception {
        ctx.setUserSession(login(ctx.getUsername(), ctx.getPassword()));
    }

    @假如("用户 {string} 已注册并通过审核")
    public void 用户已注册并通过审核(String username) throws Exception {
        用户注册(username, "abc12345");
        管理员审核通过();
    }

    @当("管理员停用该用户")
    public void 管理员停用() throws Exception {
        mockMvc.perform(post("/api/admin/users/{id}/disable", currentUserId())
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @当("管理员重新启用该用户")
    public void 管理员重新启用() throws Exception {
        mockMvc.perform(post("/api/admin/users/{id}/enable", currentUserId())
                        .session(adminSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @那么("该用户的下一次请求应返回未授权")
    public void 下一次请求未授权() throws Exception {
        // ActiveUserFilter 每次请求回库校验用户状态：停用即时生效，旧会话立即 401
        mockMvc.perform(get("/api/conversations").session(ctx.getUserSession()))
                .andExpect(status().isUnauthorized());
    }

    @那么("该用户重新登录应提示账号被停用")
    public void 重新登录提示停用() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
    }

    private Long currentUserId() {
        Long id = userRepository.findByUsername(ctx.getUsername()).orElseThrow().id();
        ctx.setUserId(id);
        return id;
    }

    private String loginJson() {
        return "{\"username\":\"" + ctx.getUsername() + "\",\"password\":\"" + ctx.getPassword() + "\"}";
    }

    /** 内置管理员（AdminSeedRunner 种子）登录后台，会话在场景内复用。 */
    private MockHttpSession adminSession() throws Exception {
        if (ctx.getAdminSession() == null) {
            ctx.setAdminSession(login(CucumberSpringConfig.ADMIN_USERNAME, CucumberSpringConfig.ADMIN_PASSWORD));
        }
        return ctx.getAdminSession();
    }

    private MockHttpSession login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        // MockMvc 不会依据 JSESSIONID cookie 重建会话，需显式传递登录产生的 MockHttpSession
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
