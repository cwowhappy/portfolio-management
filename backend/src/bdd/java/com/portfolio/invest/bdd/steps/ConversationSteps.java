package com.portfolio.invest.bdd.steps;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.portfolio.invest.bdd.CucumberSpringConfig;
import com.portfolio.invest.domain.conversation.ConversationErrorCode;
import com.portfolio.invest.domain.user.UserRepository;
import io.cucumber.java.zh_cn.假如;
import io.cucumber.java.zh_cn.当;
import io.cucumber.java.zh_cn.那么;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 会话管理旅程步骤：走真实 HTTP（MockMvc + 显式 MockHttpSession 传递，复用 AuthSteps 手法），
 * 验证创建/保存/读回全链路与归属隔离（非本人访问 404，不泄露存在性）。
 */
public class ConversationSteps {

    private static final String PASSWORD = "abc12345";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    ScenarioContext ctx;

    @假如("用户 {string} 已完成注册审核并登录")
    public void 用户完成注册审核并登录(String username) throws Exception {
        registerAndApprove(username);
        ctx.setUsername(username);
        ctx.setUserSession(login(username, PASSWORD));
    }

    @假如("另一用户 {string} 已完成注册审核并登录")
    public void 另一用户完成注册审核并登录(String username) throws Exception {
        registerAndApprove(username);
        ctx.setOtherUserSession(login(username, PASSWORD));
    }

    @当("该用户创建一个新会话")
    public void 创建新会话() throws Exception {
        String conversationId = UUID.randomUUID().toString();
        ctx.setConversationId(conversationId);
        mockMvc.perform(post("/api/conversations").session(ctx.getUserSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + conversationId + "\"}"))
                .andExpect(status().isCreated());
    }

    @那么("会话创建成功且默认标题为 {string}")
    public void 会话创建成功且默认标题(String defaultTitle) throws Exception {
        mockMvc.perform(get("/api/conversations").session(ctx.getUserSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ctx.getConversationId()))
                .andExpect(jsonPath("$[0].title").value(defaultTitle));
    }

    @当("该用户保存首条消息 {string}")
    public void 保存首条消息(String content) throws Exception {
        mockMvc.perform(put("/api/conversations/{id}/messages", ctx.getConversationId())
                        .session(ctx.getUserSession())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"id\":\"m-1\",\"role\":\"user\",\"content\":\"" + content
                                + "\",\"createdAt\":1700000000000}]"))
                .andExpect(status().isNoContent());
    }

    @那么("该用户可以读回消息 {string}")
    public void 读回消息(String content) throws Exception {
        mockMvc.perform(get("/api/conversations/{id}/messages", ctx.getConversationId())
                        .session(ctx.getUserSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("m-1"))
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[0].content").value(content));
    }

    @那么("会话标题应变为 {string}")
    public void 会话标题应变为(String title) throws Exception {
        mockMvc.perform(get("/api/conversations").session(ctx.getUserSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ctx.getConversationId()))
                .andExpect(jsonPath("$[0].title").value(title));
    }

    @那么("另一用户访问该会话消息应返回404")
    public void 另一用户访问返回404() throws Exception {
        mockMvc.perform(get("/api/conversations/{id}/messages", ctx.getConversationId())
                        .session(ctx.getOtherUserSession()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ConversationErrorCode.NOT_FOUND));
    }

    private void registerAndApprove(String username) throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isCreated());
        Long userId = userRepository.findByUsername(username).orElseThrow().id();
        mockMvc.perform(post("/api/admin/users/{id}/approve", userId).session(adminSession()))
                .andExpect(status().isOk());
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
